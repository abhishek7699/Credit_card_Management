# Kafka Consumer Contract: transaction.status.updated

**Service**: notification-service (consumer)
**Producer**: authorization-service
**Topic**: `transaction.status.updated`
**Consumer Group**: `notification-service-consumer`
**Offset Reset**: `earliest`
**Ack Mode**: `MANUAL_IMMEDIATE`

---

## Consumed Event Schema

```json
{
  "eventId": "string (UUID as string)",
  "eventType": "string — one of: TransactionAuthorized | TransactionDeclined | TransactionFailed",
  "transactionId": "string (UUID)",
  "cardToken": "string (opaque vault token, never a raw PAN)",
  "reasonCode": "string | null — null when eventType=TransactionAuthorized; e.g., INSUFFICIENT_CREDIT, INVALID_CARD, CARD_NOT_ACTIVE when declined/failed",
  "occurredAt": "ISO-8601 timestamp"
}
```

**Example — Authorized**:
```json
{
  "eventId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "eventType": "TransactionAuthorized",
  "transactionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "cardToken": "vault:v1:tok_abc123",
  "reasonCode": null,
  "occurredAt": "2026-06-12T14:30:00Z"
}
```

**Example — Declined**:
```json
{
  "eventId": "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11",
  "eventType": "TransactionDeclined",
  "transactionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "cardToken": "vault:v1:tok_abc123",
  "reasonCode": "INSUFFICIENT_CREDIT",
  "occurredAt": "2026-06-12T14:30:01Z"
}
```

---

## Idempotency Key

Format: `notif:{eventType}:{eventId}`

Example: `notif:TransactionAuthorized:f47ac10b-58cc-4372-a567-0e02b2c3d479`

---

## Processing Rules

1. If `eventId` or `transactionId` is null → discard, log WARN, ack offset, no record created.
2. Check `processed_events` for the idempotency key. If found → skip, ack offset.
3. Look up `CardholderContact` by `cardToken`. If not found → ack offset, log WARN, no notification.
4. If `transaction_notifications_enabled = false` → ack offset, no notification (transaction events only; fraud alerts bypass this).
5. For each registered channel (email / SMS) → create `NotificationRecord` with status `PENDING`.
6. Commit `ProcessedEvent` + `NotificationRecord`(s) atomically.
7. Attempt channel send for each record → update status to `SENT` or `FAILED`.
8. Ack offset in `finally` block (always acknowledges, even on partial failure).

---

## Notification Content

| Event Type | Subject | Body |
|------------|---------|------|
| `TransactionAuthorized` | "Payment Approved" | "Payment approved: Card ending in {last4}. Reference: {transactionId}." |
| `TransactionDeclined` | "Payment Declined" | "Payment declined: Card ending in {last4}. Reason: {reasonCode}. Reference: {transactionId}." |
| `TransactionFailed` | "Payment Failed" | "Payment failed: Card ending in {last4}. Reason: {reasonCode}. Reference: {transactionId}." |

`{last4}` is extracted from `CardholderContact.maskedCardNumber` (last 4 characters before end of string).
