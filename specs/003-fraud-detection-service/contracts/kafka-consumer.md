# Kafka Consumer Contract: TransactionInitiated

**Service**: fraud-detection-service
**Direction**: INBOUND (consumed)
**Topic**: `transaction.initiated`
**Consumer Group**: `fraud-detection-service-consumer`

## Event Schema

```json
{
  "eventId":       "string (non-null, non-blank) — unique event identifier; used as idempotency key",
  "eventType":     "string — always \"TransactionInitiated\"",
  "transactionId": "UUID (non-null) — unique transaction identifier",
  "cardToken":     "string (non-null, non-blank) — opaque vault token; NEVER a raw PAN",
  "amount":        "decimal (non-null, > 0) — transaction amount",
  "currencyCode":  "string (3 chars, non-null) — ISO 4217 currency code",
  "occurredAt":    "ISO-8601 instant (non-null) — when the transaction was initiated"
}
```

## Example Payload

```json
{
  "eventId":       "evt-a1b2c3d4",
  "eventType":     "TransactionInitiated",
  "transactionId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "cardToken":     "vault:v1:AbCdEfGhIjKlMnOpQrSt",
  "amount":        250.00,
  "currencyCode":  "USD",
  "occurredAt":    "2026-06-12T14:30:00.000Z"
}
```

## Processing Semantics

- **Idempotency key**: `fraud:TransactionInitiated:{eventId}`
- **Duplicate handling**: If `processed_events` already contains this key, the event is
  silently discarded and the offset is acknowledged. No alert is raised, no transaction
  record is written.
- **Malformed event handling** (FR-010): If the payload is null or required fields
  (`transactionId`, `cardToken`, `amount`, `currencyCode`, `occurredAt`, `eventId`) are
  missing or invalid, a WARN log is emitted and the offset is acknowledged. No alert is
  raised; processing does not halt.
- **Offset commit**: Committed only after all DB writes succeed and the FraudAlertRaised
  event (if any) is published to Kafka.

## Shared Contract with Authorization Service

The `TransactionInitiatedEvent` schema is shared with the `authorization-service`.
Both services consume from `transaction.initiated` using the same JSON schema and the same
Java class structure. The `cardToken` field in both services is the same opaque token
produced by `transaction-service`.

The `trusted.packages` Spring Kafka property for deserialization:
`com.creditcard.frauddetection.kafka.event`
