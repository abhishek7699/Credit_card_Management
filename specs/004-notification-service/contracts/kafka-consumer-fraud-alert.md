# Kafka Consumer Contract: fraud.alert.raised

**Service**: notification-service (consumer)
**Producer**: fraud-detection-service
**Topic**: `fraud.alert.raised`
**Consumer Group**: `notification-service-consumer`
**Offset Reset**: `earliest`
**Ack Mode**: `MANUAL_IMMEDIATE`

---

## Consumed Event Schema

```json
{
  "eventId": "string (UUID as string)",
  "eventType": "FraudAlertRaised",
  "alertId": "string (UUID)",
  "transactionId": "string (UUID)",
  "cardToken": "string (opaque vault token, never a raw PAN)",
  "triggeredRuleTypes": ["VELOCITY_EXCEEDED", "AMOUNT_ANOMALY", "BLACKLISTED_CARD"],
  "detectedAt": "ISO-8601 timestamp",
  "sourceService": "fraud-detection-service",
  "traceId": "string | null"
}
```

**Example**:
```json
{
  "eventId": "550e8400-e29b-41d4-a716-446655440000",
  "eventType": "FraudAlertRaised",
  "alertId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "transactionId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "cardToken": "vault:v1:tok_abc123",
  "triggeredRuleTypes": ["VELOCITY_EXCEEDED", "BLACKLISTED_CARD"],
  "detectedAt": "2026-06-12T14:30:02Z",
  "sourceService": "fraud-detection-service",
  "traceId": "abc123def456"
}
```

---

## Idempotency Key

Format: `notif:FraudAlertRaised:{eventId}`

Example: `notif:FraudAlertRaised:550e8400-e29b-41d4-a716-446655440000`

---

## Processing Rules

1. If `eventId` or `transactionId` is null → discard, log WARN, ack offset, no record created.
2. Check `processed_events` for the idempotency key. If found → skip, ack offset.
3. Look up `CardholderContact` by `cardToken`. If not found → ack offset, log WARN, no notification.
4. **Fraud alerts bypass `transaction_notifications_enabled`** — send on all registered channels regardless of preference flag.
5. For each registered channel (email AND SMS, if both present) → create one `NotificationRecord` per channel with status `PENDING`.
6. Commit `ProcessedEvent` + `NotificationRecord`(s) atomically.
7. Attempt channel send for each record → update status to `SENT` or `FAILED`.
8. Ack offset in `finally` block (always acknowledges, even on partial failure).
9. Multiple triggered rule types → consolidated into a single notification per channel (not one notification per rule).

---

## Notification Content

| Channel | Subject | Body |
|---------|---------|------|
| EMAIL | "URGENT: Suspicious Activity Detected" | "Suspicious activity detected on card ending in {last4}. Triggered by: {rules}. Alert ID: {alertId}. Contact us immediately if this was not you." |
| SMS | (no subject) | "ALERT: Suspicious activity on card ending in {last4}. Triggered by: {rules}. Contact us if not you." |

`{rules}` is a comma-separated list of triggered rule types.
`{last4}` is extracted from `CardholderContact.maskedCardNumber`.
