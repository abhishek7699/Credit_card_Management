# Event Contract: Booking Service

## Outbound (gRPC, not Kafka): `transaction-service.InitiateTransaction`

booking-service calls the **existing, unmodified** `TransactionService.InitiateTransaction`
RPC (`proto/v1/transaction_service.proto`) when a booking is first HELD (FR-009):

| Field | Value |
|-------|-------|
| `card_number`, `card_expiry`, `cvv`, `cardholder_name` | taken verbatim from the booking request, never persisted by booking-service |
| `amount` | `nightly_rate × number of nights` |
| `currency_code` | fixed, e.g. `"USD"` (no multi-currency in this MVP) |
| `merchant_id` | `booking.merchant-id` config property (default `HOTEL-001`) — see research.md §2 |

The response's `transaction_id` is stored on the booking (FR-009); no other field of
this contract changes — `transaction-service` itself is untouched (FR-018).

**Deadline**: the call is made with a 5-second gRPC deadline
(`withDeadlineAfter(5, TimeUnit.SECONDS)` — research.md §2). On `DEADLINE_EXCEEDED` or
`UNAVAILABLE`, the already-HELD booking is simply left HELD with no `transaction_id` set
— the same outcome as the spec's "payment system temporarily unreachable" edge case.
Hold expiry (FR-015), not a retry here, is what eventually resolves it.

## Inbound (Kafka): `transaction.status.updated`

booking-service subscribes with consumer group `booking-service`, idempotently
(Principle III — `processed_events` key `booking:transaction.status.updated:{eventId}`).

**Envelope** (local DTO copy, same shape as every other consumer's — research.md §3):

```json
{
  "eventId": "uuid",
  "eventType": "transaction.status.updated",
  "sourceService": "transaction-service",
  "traceId": "uuid",
  "occurredAt": "2026-10-29T12:00:00Z",
  "payload": {
    "transactionId": "txn-uuid",
    "cardToken": "tok_...",
    "outcome": "AUTHORIZED",
    "reasonCode": null
  }
}
```

**Handling rule** — lookup the booking by `payload.transactionId`, then:

| Booking state when event arrives | `outcome` | Action (FR) |
|---|---|---|
| **No booking found for `payload.transactionId`** | any | **Not a no-op.** Do not write `processed_events`; throw so the Kafka error handler retries with bounded backoff (this is the race described in research.md §3a, not a genuine not-found). Only after retries are exhausted, log ERROR. |
| HELD | `AUTHORIZED` | → CONFIRMED (FR-010) |
| HELD | `DECLINED` / `FAILED` | → CANCELLED, reason `PAYMENT_DECLINED`/`PAYMENT_FAILED`, **and delete this booking's `booking_room_nights` rows in the same transaction** — room released (FR-011) |
| CANCELLED (via `HOLD_EXPIRED`) | `AUTHORIZED` | **stays CANCELLED** — no reverse transition; set `cancellationReason = PAYMENT_AFTER_EXPIRY`, log WARN for manual refund; `booking_room_nights` rows were already deleted at expiry time, nothing to do there (FR-019, FR-023) |
| CANCELLED (any reason) | `DECLINED` / `FAILED` | no-op — no state change (FR-021) |
| CONFIRMED or CANCELLED (any reason) | any outcome, event already processed | no-op — idempotency key already recorded (FR-012) |

No case in this table ever moves a booking out of `CONFIRMED` or `CANCELLED` once
reached (FR-023) — this is the full handling matrix; there is no silent-drop path for an
`AUTHORIZED` outcome (FR-016), and the "no booking found" race is handled as a retry, not
a drop, either.
