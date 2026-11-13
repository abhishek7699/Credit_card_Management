# Contract: PaymentProcessor (authorization-service, internal Java interface)

Owned by authorization-service. Called only from the service layer
(constitution Principle V: no DB or network calls from the Kafka listener).

Processor-neutral (spec 014, refactor after phase 1). No method takes a card row. Each implementation
does its own lookups and locking. The Mastercard path never touches `card_accounts`.

Implementations:
- `SimulatedProcessor` (phase 1, default). Locks the card row itself, then checks the timeout marker.
- `MastercardGatewayProcessor` (phase 2). Uses `libs/mastercard-gateway`. Local state is only the
  authorization records and the timeout markers.

Selected by `payment.processor` (`simulated` default, `mastercard`). Any other value fails at startup
(`ProcessorConfig`).

```java
public interface PaymentProcessor {

    /**
     * Decide one authorization. Approval reserves the amount. The authorization record is written by the
     * service, not here. Returns TIMED_OUT_SKIPPED without deciding when a timeout marker exists (013).
     */
    ProcessorDecision authorize(AuthorizeRequest request);

    /** What happened to a transaction at the processor. Used after UNKNOWN. Simulator: always UNKNOWN. */
    ProcessorDecision lookup(UUID transactionId);

    /** Capture an authorized transaction. Simulator: CAPTURE_REJECTED. Mastercard: phase 4 (throws until then). */
    ProcessorDecision capture(UUID transactionId, BigDecimal amount, String currencyCode);

    /**
     * Give back a hold placed by authorize, before capture.
     * NO_AUTHORIZATION when no authorization record exists (the service writes the timeout marker).
     * NO_OP when a record exists but nothing is held (for example DECLINED).
     * RELEASED when the hold was given back. RELEASE_REJECTED when the processor refused, or the outcome
     * of the void is unknown (reason VOID_UNKNOWN).
     */
    ProcessorDecision releaseHold(UUID transactionId, String paymentToken);
}
```

`AuthorizeRequest` (processor-neutral):

| Field | Notes |
|---|---|
| `transactionId` (UUID) | Our ID. The gateway's order ID and transaction ID for the AUTHORIZE. |
| `amount`, `currencyCode`, `merchantId` | As received. |
| `paymentToken` | Simulator: Vault card token. Mastercard: the payment reference (Vault token in phase 2; payment session in phase 3). Never a card number. `toString()` omits it. |

`releaseHold(transactionId, paymentToken)`: the simulator uses `paymentToken` to find the card row to
lock. Mastercard ignores it.

## Behavior rules (all implementations)

| Input | Required result |
|---|---|
| Approved | `APPROVED` |
| Declined | `DECLINED`. `reasonCode` is a `ReasonCode`. An external decline is `PROCESSOR_DECLINED`, and the processor's own code is kept in `processorCode` (stored as `processor_reason_code`). |
| Timeout / connection error / 5xx / malformed / `UNKNOWN` | The processor calls `lookup(transactionId)` itself and decides from the lookup only. If the lookup is also unknown, return `UNKNOWN`. The service leaves the transaction PENDING, and the 013 sweep fails it later. |
| Duplicate identifier at Mastercard | Treated as "already processed": `lookup`, never a second AUTHORIZE. |
| `releaseHold` with no authorization record | `NO_AUTHORIZATION`. No VOID sent, no `available_credit` change. The service writes the timeout marker. |
| `releaseHold` with a record that is not AUTHORIZED (for example DECLINED) | `NO_OP`. No VOID, no change, no marker. |
| `releaseHold` on a captured transaction | `RELEASE_REJECTED`, reason recorded, no refund. |

## Concurrency rules

- `authorize` and `releaseHold` for one transaction must not interleave.
- **Simulator**: the processor takes the card row lock (`findByCardTokenForUpdate`), then checks the
  timeout marker, then decides. This is the 013 ordering. It is what serializes authorize and release.
- **Mastercard**: there is no card row, so no lock. The marker is checked before the gateway call. A
  timeout release racing an in-flight authorize is protected only by 013 timer rule 1
  (P ≥ K+M+R+m). **Open risk, owner decision.**

## Service-layer responsibilities (not on the processor)

These stay in authorization-service's service layer, outside the interface, because they gate
`authorize` for every processor:

- **Duplicate check** for `transaction.timed_out`: `processed_events` key `TransactionTimedOut:<eventId>` (013).
- **Timeout marker**: written when `releaseHold` returns `NO_AUTHORIZATION`. A late `transaction.initiated`
  finds it (the processor checks it) and skips authorization.
- **Authorization record**: written by `AuthorizationService` after an APPROVED or DECLINED decision,
  for every processor. `releaseHold` updates the records it releases (AUTHORIZED → RELEASED).
- **Result mapping**: APPROVED → AUTHORIZED result. DECLINED → DECLINED result with the reason. Anything
  else → no result (PENDING).

## Simulator-specific

- `authorize` reproduces the `card_accounts` logic exactly (phase 1; characterization tests).
- `releaseHold` restores `available_credit` once per AUTHORIZED record and marks it RELEASED. A second call
  finds nothing to release and returns `NO_OP`.

## Mastercard-specific (phase 2)

- AUTHORIZE: `PUT /api/rest/version/{mastercard.api-version}/merchant/{id}/order/{transactionId}/transaction/{transactionId}`,
  body `apiOperation=AUTHORIZE`, `order.amount`, `order.currency`, `sourceOfFunds.session.id` = `paymentToken`.
- Status lookup: `GET` on the same order/transaction path.
- VOID: `PUT` on `…/order/{transactionId}/transaction/{voidId}`, body `apiOperation=VOID`,
  `transaction.targetTransactionId` = `transactionId`. **`voidId` is not `transactionId`**: Mastercard needs
  a unique transaction ID per transaction on an order. `voidId` is `UUID.nameUUIDFromBytes(transactionId + ":VOID")`,
  so a retried VOID reuses the same ID. This deviates from "use our transactionId" and needs owner approval.
- Unknown VOID: `GET` on the VOID's path. `APPROVED` means voided. Anything else means `RELEASE_REJECTED`
  with `VOID_UNKNOWN`, and the hold stays.
- Auth: HTTP Basic `merchant.<id>` : password, from env only. HTTPS only, with certificate verification.
- Logs: the operation, `transactionId`, outcome and gateway code. Never a session ID, password, merchant ID,
  or request/response body (tested by `MastercardGatewayLogSecretTest`).
