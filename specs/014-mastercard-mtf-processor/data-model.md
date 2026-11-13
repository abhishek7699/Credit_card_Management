# Data model: 014 Mastercard processor

No card data is stored anywhere in this model. The session ID is carried in one event and is
not persisted.

## Transaction (transaction-service, existing `transactions` table)

Existing fields are unchanged: `id`, `card_token`, `masked_card_number`, `cardholder_name`,
`amount`, `currency_code`, `status`, `created_at`, `updated_at`, `version`.

New columns (one Flyway migration, additive, nullable):

| Column | Type | Set when | Meaning |
|---|---|---|---|
| `failure_reason` | VARCHAR(32) | Spec 013 sweep (`TIMEOUT`); processor decline (gateway reason) | Why the transaction ended FAILED or DECLINED |
| `decline_reason` | VARCHAR(64) | Processor DECLINED | Mastercard `response.gatewayCode` (or simulator reason code) |
| `processor` | VARCHAR(16) | Authorization outcome | `SIMULATED` or `MASTERCARD` (which processor decided) |
| `processor_reference` | VARCHAR(64) | AUTHORIZED or CAPTURED | Mastercard transaction reference, or null for simulator |

State transitions (unchanged lifecycle; no new statuses):

```
PENDING --authorize approved--> AUTHORIZED --capture ok (phase 4)--> SETTLED
PENDING --authorize declined--> DECLINED
PENDING --unknown, lookup says approved--> AUTHORIZED
PENDING --unknown, lookup says declined--> DECLINED
PENDING --unknown, lookup unknown--> PENDING (reported, not changed)
PENDING --013 timeout--> FAILED (failure_reason = TIMEOUT)
```

Every transition is a conditional update on the current status (`WHERE id = ? AND status = ?`).
A late result never overwrites a terminal state.

## Authorization record (authorization-service, existing `authorization_records`)

Existing fields kept. New:

| Column | Type | Meaning |
|---|---|---|
| `processor` | VARCHAR(16) | `SIMULATED` or `MASTERCARD` |
| `processor_reference` | VARCHAR(64) | Mastercard transaction id (nullable for simulator) |
| `release_status` | VARCHAR(16) | `NONE`, `RELEASED`, `RELEASE_REJECTED` (set by `releaseHold`) |

For the simulator, `card_accounts` is still the only balance store; a release adds the amount
back there. For Mastercard, no `card_accounts` row is read or written.

## Processor decision (value object, not persisted)

Returned by `PaymentProcessor` operations.

| Field | Meaning |
|---|---|
| `outcome` | `APPROVED`, `DECLINED`, `UNKNOWN`, `RELEASED`, `RELEASE_REJECTED`, `CAPTURED`, `CAPTURE_REJECTED` |
| `reasonCode` | Gateway or simulator code, safe to store and log |
| `reference` | Mastercard transaction id, or null |

It never contains a card number, expiry, CVV, session payload, or credential.

## Payment session reference (transient, in event payload only)

| Field | Where | Rule |
|---|---|---|
| `paymentSessionId` | gRPC request, `transaction.initiated` payload | Optional. Present only on the session path. Never persisted. Not logged in full (mask all but the last 4 characters). |

## Processor configuration (not persisted)

| Key | Source | Rule |
|---|---|---|
| `payment.processor` | property | `simulated` (default) or `mastercard`. Other values fail startup. |
| `mastercard.api-version` | property | `100` (provisional). Single value for all paths. |
| `mastercard.base-url` | `MASTERCARD_BASE_URL` | MTF host. Required when `mastercard`. |
| `mastercard.merchant-id` | `MASTERCARD_MERCHANT_ID` | `TEST` prefix on MTF. Masked in `toString()`. |
| `mastercard.api-password` | `MASTERCARD_API_PASSWORD` | Masked in `toString()`. Never logged. |
