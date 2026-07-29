# Kafka Producer Contract: FraudAlertRaised

**Service**: fraud-detection-service
**Direction**: OUTBOUND (produced)
**Topic**: `fraud.alert.raised`
**Message Key**: `transactionId` (UUID as string)

## Trigger Condition

A `FraudAlertRaised` event is published if and only if at least one fraud rule triggers
for the evaluated transaction. Clean transactions (zero rules triggered) produce no event.

## Event Schema

```json
{
  "eventId":            "UUID (non-null) — unique identifier for this alert event",
  "eventType":          "string — always \"FraudAlertRaised\"",
  "alertId":            "UUID (non-null) — identifier of the persisted FraudAlert record",
  "transactionId":      "UUID (non-null) — transaction that triggered the alert",
  "cardToken":          "string (non-null) — opaque vault token; NEVER a raw PAN",
  "triggeredRuleTypes": "array<string> (non-null, non-empty) — list of triggered rule names",
  "detectedAt":         "ISO-8601 instant (non-null) — timestamp of alert detection",
  "sourceService":      "string — always \"fraud-detection-service\"",
  "traceId":            "string (nullable) — Micrometer trace ID propagated from inbound event"
}
```

## Example Payload

```json
{
  "eventId":            "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "eventType":          "FraudAlertRaised",
  "alertId":            "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "transactionId":      "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "cardToken":          "vault:v1:AbCdEfGhIjKlMnOpQrSt",
  "triggeredRuleTypes": ["VELOCITY_EXCEEDED", "AMOUNT_ANOMALY"],
  "detectedAt":         "2026-06-12T14:30:00.123Z",
  "sourceService":      "fraud-detection-service",
  "traceId":            "abc123def456"
}
```

## Valid `triggeredRuleTypes` Values

| Value | Description |
|-------|-------------|
| `VELOCITY_EXCEEDED` | Card exceeded the configured maximum transaction count in the rolling time window |
| `AMOUNT_ANOMALY` | Transaction amount exceeds the configured multiplier × card's average spend |
| `BLACKLISTED_CARD` | Card token appears in the active blacklist |

Multiple values may appear in a single event when a transaction triggers more than one rule.

## Publishing Guarantee

- The event is published **after** the `FraudAlert` record is successfully committed to
  CockroachDB. If DB persistence fails, no event is published.
- If the Kafka publish fails after DB commit, the Kafka offset is not acknowledged. On
  redelivery, the idempotency check prevents duplicate DB writes; the Kafka publish is
  retried.
- **Producer config**: `acks=all`, `enable.idempotence=true`, `retries=3` — ensures
  at-least-once delivery to the broker with no duplicates from the producer side.

## Downstream Consumers

Expected consumers of `fraud.alert.raised`:
- `notification-service` — sends fraud alert notifications to cardholders
- `settlement-service` — may hold or flag settlements on alerted transactions
- `reporting-service` — aggregates fraud metrics

These services act on the event independently and asynchronously. The `fraud-detection-service`
does not make real-time authorization decisions and does not block transaction processing.
