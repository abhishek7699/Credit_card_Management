# Kafka Event Contracts: Settlement Service

---

## Inbound: TransactionAuthorizedEvent

**Topic**: `transaction.authorized`
**Publisher**: `authorization-service`
**Consumer**: `settlement-service` (group: `settlement-service-consumer`)

**⚠️ PREREQUISITE**: The `authorization-service` MUST add `merchantId` to this event before settlement-service implementation begins. This field is not present in the current `TransactionInitiatedEvent` schema.

### Schema

```json
{
  "eventId": "string (UUID)",
  "eventType": "TransactionAuthorized",
  "sourceService": "authorization-service",
  "traceId": "string",
  "occurredAt": "2026-06-13T23:45:00.000Z",
  "payload": {
    "transactionId": "string (UUID)",
    "merchantId": "string (required, non-null)",
    "cardToken": "string (vault token, e.g. vault:v1:...)",
    "maskedCardNumber": "string (e.g. ****-****-****-1234)",
    "amount": "decimal string (e.g. 250.00)",
    "currencyCode": "string (ISO 4217, e.g. USD)",
    "authorizedAt": "2026-06-13T23:45:00.000Z"
  }
}
```

### Field Rules

| Field | Required | Validation |
|-------|----------|------------|
| `eventId` | YES | Non-null; used as idempotency key (`settlement:TransactionAuthorized:{eventId}`) |
| `eventType` | YES | Must equal `"TransactionAuthorized"` |
| `payload.transactionId` | YES | Non-null UUID; PRIMARY KEY in `settlement_transactions` |
| `payload.merchantId` | YES | Non-null, non-empty; required for merchant grouping |
| `payload.cardToken` | YES | Vault token format; never raw PAN |
| `payload.maskedCardNumber` | YES | Display-safe masked format |
| `payload.amount` | YES | > 0; zero-amount transactions are skipped by batch processor |
| `payload.currencyCode` | YES | `USD` for v1 |
| `payload.authorizedAt` | YES | ISO-8601 instant |

### Consumer Behavior

1. Null `eventId` → discard, log WARN, ack
2. Null `payload.transactionId` → discard, log WARN, ack
3. Null `payload.merchantId` → insert with `merchantId = null`; batch processor skips these with WARN log
4. Duplicate `eventId` (already in `processed_events`) → discard, ack (idempotent)
5. Valid event → insert into `settlement_transactions` with `status = AUTHORIZED`

---

## Outbound: TransactionSettledEvent

**Topic**: `transaction.settled`
**Publisher**: `settlement-service`
**Consumers**: `transaction-service` (to update its own transaction status), `reporting-service`

### Schema

```json
{
  "eventId": "string (UUID, generated per event)",
  "eventType": "TransactionSettled",
  "sourceService": "settlement-service",
  "traceId": "string",
  "occurredAt": "2026-06-13T23:59:47.123Z",
  "payload": {
    "transactionId": "string (UUID)",
    "merchantId": "string",
    "batchId": "string (UUID of the SettlementBatch)",
    "grossAmount": "decimal string (e.g. 250.00)",
    "interchangeFee": "decimal string (e.g. 5.00)",
    "netAmount": "decimal string (e.g. 245.00)",
    "currencyCode": "string",
    "settlementDate": "2026-06-13",
    "settledAt": "2026-06-13T23:59:47.123Z"
  }
}
```

### Field Rules

| Field | Description |
|-------|-------------|
| `payload.transactionId` | References the original transaction |
| `payload.merchantId` | Merchant that receives the net settlement |
| `payload.batchId` | Identifies the batch run; links to `settlement_batches` record |
| `payload.grossAmount` | Original authorized transaction amount |
| `payload.interchangeFee` | Fee deducted (rate × grossAmount, HALF_UP rounding) |
| `payload.netAmount` | grossAmount − interchangeFee |
| `payload.settlementDate` | Calendar date of the batch run (UTC) |

### Publishing Behavior

- Published once per settled transaction inside `SettlementItemWriter.write()`
- Published **after** the chunk DB transaction commits
- Kafka publish failure: caught, logged as WARN; settlement DB record is NOT rolled back
- Producer configured with `acks=all`, `retries=3`, `enable.idempotence=true`
- No event published for skipped (already-SETTLED) transactions
- No event published if the settlement DB write fails

---

## Topic Configuration

| Topic | Partitions | Replication Factor | Retention |
|-------|------------|-------------------|-----------|
| `transaction.authorized` | 6 | 3 | 7 days |
| `transaction.settled` | 6 | 3 | 7 days |

---

## Kafka Consumer Configuration

| Setting | Value | Rationale |
|---------|-------|-----------|
| `group-id` | `settlement-service-consumer` | Unique consumer group |
| `auto-offset-reset` | `earliest` | Catch up on startup (set programmatically for @EmbeddedKafka reliability) |
| `enable-auto-commit` | `false` | Manual acknowledgement (`AckMode.MANUAL_IMMEDIATE`) |
| `isolation-level` | `read_committed` | Skip uncommitted Kafka transactions |
| `use.type.headers` | `false` | Explicit target class per consumer factory |
