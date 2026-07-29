# Kafka Event Contracts: Transaction Service

**Phase**: 1 — Design
**Date**: 2026-06-11
**Service**: `transaction-service`

All events use JSON serialization. Every event envelope MUST include the standard fields
defined below (Principle VI). The `traceId` ties all events in a single payment flow
together across service boundaries.

---

## Event Envelope (all events)

Every Kafka event produced or consumed by the transaction-service MUST conform to this
base structure:

```json
{
  "eventId":      "<UUID>",        // unique per event emission; used as idempotency key
  "eventType":    "<string>",      // discriminator — see event types below
  "sourceService":"<string>",      // service that published this event
  "traceId":      "<string>",      // Micrometer trace ID; propagated via Kafka header
  "occurredAt":   "<ISO-8601>",    // event timestamp; UTC
  "payload":      { ... }          // event-specific fields
}
```

---

## Events Produced by transaction-service

### TransactionInitiated

Published to the `transaction.initiated` Kafka topic immediately after a transaction
record is persisted with PENDING status.

**Topic**: `transaction.initiated`
**Key**: `transactionId` (UUID string) — ensures ordering per transaction
**Partitions**: 6 (default; adjust for throughput at scale)
**Consumers**: `authorization-service`, `fraud-detection-service`

```json
{
  "eventId":      "550e8400-e29b-41d4-a716-446655440000",
  "eventType":    "TransactionInitiated",
  "sourceService":"transaction-service",
  "traceId":      "64c6f34a2b8e4d9f",
  "occurredAt":   "2026-06-11T10:30:00.000Z",
  "payload": {
    "transactionId":    "7c9e6679-7425-40de-944b-e07fc1f90ae7",
    "cardToken":        "vault:v1:8SDd3WHDOjf7mq69CyCqYjBXAiQQAVZRkFM13ok481zo",
    "maskedCardNumber": "****-****-****-1234",
    "cardholderName":   "Jane Smith",
    "amount":           "99.99",
    "currencyCode":     "USD"
  }
}
```

**Security notes**:
- `cardToken` is the opaque Vault Transit ciphertext — downstream services use this token
  to request decryption from Vault if they need the real PAN (e.g., `authorization-service`).
- Raw card number, CVV, and expiry are NEVER included in this event (Principle I).
- `maskedCardNumber` is included for display/logging purposes only.

---

## Events Consumed by transaction-service

All status-update events are consumed from the `transaction.status.updated` topic.
The `transaction-service` consumer group ID: `transaction-service-status-consumer`.

### TransactionAuthorized

Published by `authorization-service` when the card network approves the payment.
Causes `transaction-service` to update transaction status to AUTHORIZED.

**Topic**: `transaction.status.updated`

```json
{
  "eventId":      "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "eventType":    "TransactionAuthorized",
  "sourceService":"authorization-service",
  "traceId":      "64c6f34a2b8e4d9f",
  "occurredAt":   "2026-06-11T10:30:01.500Z",
  "payload": {
    "transactionId":    "7c9e6679-7425-40de-944b-e07fc1f90ae7",
    "authorizationCode":"AUTH-XK92P1"
  }
}
```

### TransactionDeclined

Published by `authorization-service` when the card network declines the payment.
Causes `transaction-service` to update transaction status to DECLINED.

**Topic**: `transaction.status.updated`

```json
{
  "eventId":      "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "eventType":    "TransactionDeclined",
  "sourceService":"authorization-service",
  "traceId":      "64c6f34a2b8e4d9f",
  "occurredAt":   "2026-06-11T10:30:01.800Z",
  "payload": {
    "transactionId":    "7c9e6679-7425-40de-944b-e07fc1f90ae7",
    "declineCode":      "INSUFFICIENT_FUNDS",
    "declineMessage":   "Insufficient funds"
  }
}
```

### TransactionFailed

Published by `authorization-service` or `fraud-detection-service` when an unrecoverable
system error prevents the payment from being processed.
Causes `transaction-service` to update transaction status to FAILED.

**Topic**: `transaction.status.updated`

```json
{
  "eventId":      "1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed",
  "eventType":    "TransactionFailed",
  "sourceService":"authorization-service",
  "traceId":      "64c6f34a2b8e4d9f",
  "occurredAt":   "2026-06-11T10:30:02.100Z",
  "payload": {
    "transactionId":    "7c9e6679-7425-40de-944b-e07fc1f90ae7",
    "failureReason":    "CARD_NETWORK_TIMEOUT",
    "failureMessage":   "Card network did not respond within the allowed window"
  }
}
```

---

## Idempotency Key Construction

For all consumed events, the idempotency key stored in `processed_events` is:

```
eventKey = "tx:" + eventType + ":" + eventId

Examples:
  "tx:TransactionAuthorized:3fa85f64-5717-4562-b3fc-2c963f66afa6"
  "tx:TransactionDeclined:9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d"
  "tx:TransactionFailed:1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed"
```

The `eventId` uniqueness is guaranteed by the publishing service. If the same event is
delivered twice, the second delivery finds the `eventKey` already in `processed_events`
and is silently acknowledged with no state change (Principle III).

---

## Kafka Topic Summary

| Topic | Direction | Key | Consumers |
|-------|-----------|-----|-----------|
| `transaction.initiated` | PRODUCED | `transactionId` | `authorization-service`, `fraud-detection-service` |
| `transaction.status.updated` | CONSUMED | `transactionId` | `transaction-service` (group: `transaction-service-status-consumer`) |
