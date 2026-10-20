# Kafka Consumer Contract: TransactionInitiated

**Service**: fraud-detection-service
**Direction**: INBOUND (consumed)
**Topic**: `transaction.initiated`
**Consumer Group**: `fraud-detection-service-consumer`

## Event Schema

The event is an envelope with the business fields nested under `payload`, matching the
contract transaction-service actually publishes (see
specs/001-transaction-service/contracts/kafka-events.md) and the shape
authorization-service already consumes from this same topic:

```json
{
  "eventId":       "string (non-null, non-blank) — unique event identifier; used as idempotency key",
  "eventType":     "string — always \"TransactionInitiated\"",
  "sourceService": "string — always \"transaction-service\"",
  "traceId":       "string | null — Micrometer trace ID",
  "occurredAt":    "ISO-8601 instant (non-null) — when the transaction was initiated",
  "payload": {
    "transactionId":    "UUID (non-null) — unique transaction identifier",
    "cardToken":        "string (non-null, non-blank) — opaque vault token; NEVER a raw PAN",
    "maskedCardNumber": "string — masked card number; not used by fraud-detection-service",
    "cardholderName":   "string — not used by fraud-detection-service",
    "amount":           "decimal (non-null, > 0) — transaction amount",
    "currencyCode":     "string (3 chars, non-null) — ISO 4217 currency code",
    "merchantId":       "string — not used by fraud-detection-service"
  }
}
```

## Example Payload

```json
{
  "eventId":       "evt-a1b2c3d4",
  "eventType":     "TransactionInitiated",
  "sourceService": "transaction-service",
  "traceId":       "64c6f34a2b8e4d9f",
  "occurredAt":    "2026-06-12T14:30:00.000Z",
  "payload": {
    "transactionId":    "f47ac10b-58cc-4372-a567-0e02b2c3d479",
    "cardToken":        "vault:v1:AbCdEfGhIjKlMnOpQrSt",
    "maskedCardNumber": "****-****-****-1234",
    "cardholderName":   "Jane Smith",
    "amount":           250.00,
    "currencyCode":     "USD",
    "merchantId":       "merchant-001"
  }
}
```

## Processing Semantics

- **Idempotency key**: `fraud:TransactionInitiated:{eventId}` — `eventId` stays a top-level
  envelope field, so this key is unaffected by the envelope shape.
- **Duplicate handling**: If `processed_events` already contains this key, the event is
  silently discarded and the offset is acknowledged. No alert is raised, no transaction
  record is written.
- **Malformed event handling** (FR-010): If `payload` is null, or required fields within it
  (`transactionId`, `cardToken`, `amount`, `currencyCode`) are missing or invalid, a WARN log
  is emitted and the offset is acknowledged. No alert is raised; processing does not halt.
- **Offset commit**: Committed only after all DB writes succeed and the FraudAlertRaised
  event (if any) is published to Kafka.

## Shared Contract with Authorization Service

The `TransactionInitiatedEvent` envelope schema is shared with `authorization-service`.
Both services consume from `transaction.initiated` using the same JSON schema and an
equivalent Java class structure (each service keeps its own copy — there is no shared
contract module in this repo). The `cardToken` field in both services is the same opaque
token produced by `transaction-service`.

The `trusted.packages` Spring Kafka property for deserialization:
`com.creditcard.*` (covers the nested static `Payload` class).
