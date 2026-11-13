# Contract: PaymentProcessor (authorization-service, internal Java interface)

Owned by authorization-service. Called only from the service layer
(constitution Principle V: no DB or network calls from the Kafka listener).

```java
public interface PaymentProcessor {

    /** Decide whether to approve. Unknown results must come back as UNKNOWN, never thrown as DECLINED. */
    ProcessorDecision authorize(AuthorizeRequest request);

    /** Look up what happened to a transaction. Used after UNKNOWN, timeouts, duplicate-id responses. */
    ProcessorDecision lookup(String transactionId);

    /** Capture an authorized transaction. Phase 4 (Mastercard) and settlement batch only. */
    ProcessorDecision capture(String transactionId, BigDecimal amount, String currencyCode);

    /**
     * Give back a hold placed by authorize, before capture. Safe no-op (NO_OP) when no
     * authorization exists for the transaction.
     * Simulator: adds the authorized amount back to card_accounts.available_credit (the card
     * row is locked by cardToken). Mastercard: VOID on the authorization. Only valid before capture.
     * cardToken is null on the Mastercard path (session path), where it is not used.
     */
    ProcessorDecision releaseHold(String transactionId, String cardToken);
}
```

`AuthorizeRequest` carries exactly one of:

| Path | Field | Notes |
|---|---|---|
| Simulator | `cardToken` (Vault token) | Same as today |
| Mastercard | `paymentSessionId` | Never a card number |

Both carry `transactionId`, `amount`, `currencyCode`, `merchantId`.

## Behavior rules (all implementations)

| Input | Required result |
|---|---|
| Approved | `APPROVED`, with `reference` |
| Declined | `DECLINED`, with `reasonCode` |
| Timeout / connection error / malformed / `UNKNOWN` | Caller calls `lookup(transactionId)`. Outcome comes from the lookup only. If the lookup is also unknown, return `UNKNOWN`; the caller leaves the transaction PENDING. |
| Duplicate `transactionId` at Mastercard | Treated as "already processed": call `lookup`, never re-send AUTHORIZE |
| `releaseHold` on a captured transaction | `RELEASE_REJECTED`, reason recorded, no refund |
| `releaseHold` when there is no authorization for the transaction (Mastercard shows none, or no simulator authorization record) | `NO_OP`: no VOID sent, no `available_credit` change. Safe to call at any time. |

## Service-layer responsibilities (not on the processor)

These stay in authorization-service's service layer, outside the interface, because they gate
`authorize` for every processor:

- **Duplicate check** for `transaction.timed_out`: the `processed_events` key of the triggering
  event (`TransactionTimedOut:<eventId>`, spec 013). A redelivered event changes nothing.
- **Timeout marker**: when `releaseHold` returns `NO_OP` and no authorization exists, the service
  writes a TIMED_OUT marker. A late `transaction.initiated` finds it and skips authorization.
  Mastercard has no local record, so the marker is the only thing that blocks a late AUTHORIZE.
- **Card row lock** on the simulator path is taken before the marker check (013 design).

## Simulator-specific

- `authorize` reproduces today's `card_accounts` logic exactly (phase 1).
- `releaseHold` restores `available_credit` once, from the AUTHORIZED record's amount, and marks
  the record RELEASED. A second call finds nothing to release and returns `NO_OP`.

## Mastercard-specific

- `authorize` sends `sourceOfFunds.session.id` = `paymentSessionId`, `order.id` =
  `transactionId`, `transaction.id` = `transactionId`. See `mastercard-gateway.md`.
- Logs record only `transactionId`, `reasonCode`, and the masked session ID (last 4 chars).
