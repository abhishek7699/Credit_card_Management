# Kafka Event Contracts: Authorization Service

**Phase**: 1 — Design
**Date**: 2026-06-11
**Service**: `authorization-service`

All events use JSON serialization. Every event envelope MUST include the standard fields
defined below (Principle VI). The `traceId` ties all events in a single payment flow
together across service boundaries and MUST be propagated from inbound to outbound events.

---

## Event Envelope (all events)

Every Kafka event produced or consumed by the authorization-service MUST conform to this
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

## Events Consumed by authorization-service

### TransactionInitiated

Consumed from the `transaction.initiated` Kafka topic. Triggers the authorization
decision pipeline.

**Topic**: `transaction.initiated`
**Consumer group ID**: `authorization-service-consumer`
**Offset reset**: `earliest` (process all events from partition assignment)
**AckMode**: `MANUAL_IMMEDIATE` (offset committed only after full processing)

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

**Field usage by authorization-service**:

| Field | Used For |
|-------|---------|
| `eventId` | Idempotency key construction: `auth:TransactionInitiated:{eventId}` |
| `payload.transactionId` | Stored in `AuthorizationRecord`, carried in outbound event |
| `payload.cardToken` | Lookup key in `card_accounts` registry |
| `payload.amount` | Compared against `card_accounts.available_credit` |
| `payload.currencyCode` | Stored in `AuthorizationRecord` |
| `traceId` | Propagated to outbound event; included in audit log MDC |
| `maskedCardNumber` | Used in audit log only (display); never stored |
| `cardholderName` | Not used by authorization-service; ignored |

**Malformed event handling**: If any required field (`transactionId`, `cardToken`,
`amount`, `currencyCode`) is missing or unparseable, the service publishes a
`TransactionFailed` event with reason `INVALID_EVENT_PAYLOAD`, then acknowledges the Kafka
offset to prevent infinite redelivery.

---

## Events Produced by authorization-service

All result events are published to the `transaction.status.updated` topic.

**Topic**: `transaction.status.updated`
**Key**: `transactionId` (UUID string) — ensures ordering per transaction
**Producer config**: `acks=all`, `enable.idempotence=true`, `retries=3`
**Partitions**: inherits from `transaction.initiated` partition count (6 default)
**Consumers**: `transaction-service` (updates transaction status), future `notification-service`

The `traceId` from the inbound `TransactionInitiated` event MUST be propagated to the
outbound event's Kafka header and `traceId` field (Principle VI).

---

### TransactionAuthorized

Published when the card is valid, active, and has sufficient available credit.
Causes `transaction-service` to update the transaction record to `AUTHORIZED`.

```json
{
  "eventId":      "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "eventType":    "TransactionAuthorized",
  "sourceService":"authorization-service",
  "traceId":      "64c6f34a2b8e4d9f",
  "occurredAt":   "2026-06-11T10:30:00.250Z",
  "payload": {
    "transactionId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
    "outcome":       "AUTHORIZED",
    "reasonCode":    null
  }
}
```

---

### TransactionDeclined

Published when authorization is denied for a business-rule reason. The `reasonCode` field
identifies the specific cause.

Causes `transaction-service` to update the transaction record to `DECLINED`.

```json
{
  "eventId":      "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d",
  "eventType":    "TransactionDeclined",
  "sourceService":"authorization-service",
  "traceId":      "64c6f34a2b8e4d9f",
  "occurredAt":   "2026-06-11T10:30:00.280Z",
  "payload": {
    "transactionId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
    "outcome":       "DECLINED",
    "reasonCode":    "INSUFFICIENT_CREDIT"
  }
}
```

**Possible `reasonCode` values for `TransactionDeclined`**:

| Reason Code | Meaning |
|-------------|---------|
| `INVALID_CARD` | Card token not found in the issuer registry |
| `CARD_NOT_ACTIVE` | Card status is `BLOCKED` or `EXPIRED` |
| `INSUFFICIENT_CREDIT` | `requested_amount > available_credit` on the card account |

---

### TransactionFailed

Published when an unrecoverable system error prevents the authorization decision from
being completed. Causes `transaction-service` to update the transaction record to `FAILED`.

The cardholder may retry the transaction.

```json
{
  "eventId":      "1b9d6bcd-bbfd-4b2d-9b5d-ab8dfbbd4bed",
  "eventType":    "TransactionFailed",
  "sourceService":"authorization-service",
  "traceId":      "64c6f34a2b8e4d9f",
  "occurredAt":   "2026-06-11T10:30:00.320Z",
  "payload": {
    "transactionId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
    "outcome":       "FAILED",
    "reasonCode":    "INTERNAL_ERROR"
  }
}
```

**Possible `reasonCode` values for `TransactionFailed`**:

| Reason Code | Meaning |
|-------------|---------|
| `INVALID_EVENT_PAYLOAD` | Inbound event was malformed or missing required fields |
| `INTERNAL_ERROR` | Unexpected system exception during processing |

---

## Idempotency Key Construction

For all consumed events, the idempotency key stored in `processed_events` is:

```
eventKey = "auth:" + eventType + ":" + eventId

Example:
  "auth:TransactionInitiated:550e8400-e29b-41d4-a716-446655440000"
```

The `auth:` prefix disambiguates from the transaction-service prefix `tx:`. If the same
event is delivered twice, the second delivery finds the `eventKey` already in
`processed_events` and is silently acknowledged with no state change (Principle III).

---

## Kafka Topic Summary

| Topic | Direction | Key | Consumer Group |
|-------|-----------|-----|----------------|
| `transaction.initiated` | CONSUMED | `transactionId` | `authorization-service-consumer` |
| `transaction.status.updated` | PRODUCED | `transactionId` | N/A (producer) |
