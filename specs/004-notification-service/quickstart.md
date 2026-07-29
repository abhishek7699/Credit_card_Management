# Quickstart: Notification Service — Integration Scenarios

**Date**: 2026-06-12
**Spec**: specs/004-notification-service/spec.md

---

## Setup for All Scenarios

1. Register cardholder contact preferences (seeded in tests via REST or direct DB insert):
   ```
   card_token:       "vault:v1:tok_test"
   masked_card_number: "****-****-****-5678"
   email:            "test@example.com"
   phone_number:     "+14155550000"
   transaction_notifications_enabled: true
   ```

2. Start `@EmbeddedKafka` with topics: `transaction.status.updated`, `fraud.alert.raised`

3. Start Testcontainers `PostgreSQLContainer`

4. Flyway runs V1–V3 migrations automatically

---

## User Story 1 Scenarios — Transaction Status Notifications

### Scenario 1.1 — Authorized transaction notification (email + SMS)

**Given**: A cardholder contact with email + phone registered, `transactionNotificationsEnabled = true`

**When**: Publish `TransactionAuthorized` event to `transaction.status.updated` with `cardToken = "vault:v1:tok_test"`

**Then**:
- Exactly 2 `NotificationRecord` rows exist (one EMAIL, one SMS) both with status `SENT`
- `processed_events` contains one row with key `notif:TransactionAuthorized:{eventId}`
- Email gateway was called once with recipient `test@example.com`
- SMS gateway was called once with recipient `+14155550000`
- Content includes `"5678"` (last 4 of masked card)

---

### Scenario 1.2 — Declined transaction notification with reason code

**Given**: Same cardholder contact setup

**When**: Publish `TransactionDeclined` event with `reasonCode = "INSUFFICIENT_CREDIT"`

**Then**:
- 2 `NotificationRecord` rows (EMAIL + SMS) with status `SENT`
- Notification content includes `"INSUFFICIENT_CREDIT"`

---

### Scenario 1.3 — No contact registered → silent acknowledgement

**Given**: No `CardholderContact` registered for the card token in the event

**When**: Publish `TransactionAuthorized` event

**Then**:
- 0 `NotificationRecord` rows
- `processed_events` contains one row (event is processed and idempotency key saved)
- Kafka offset acknowledged

---

### Scenario 1.4 — Transaction notifications disabled → no notification sent

**Given**: Cardholder contact registered with `transactionNotificationsEnabled = false`

**When**: Publish `TransactionAuthorized` event

**Then**:
- 0 `NotificationRecord` rows
- `processed_events` contains one row
- Kafka offset acknowledged

---

### Scenario 1.5 — Duplicate event delivery → exactly one notification

**Given**: Cardholder contact with email + phone registered

**When**: Publish the same `TransactionAuthorized` event (same eventId) 5 times

**Then**:
- Exactly 2 `NotificationRecord` rows total (1 EMAIL + 1 SMS from first delivery)
- `processed_events` contains exactly 1 row
- Email gateway called exactly once, SMS gateway called exactly once

---

## User Story 2 Scenarios — Fraud Alert Notifications

### Scenario 2.1 — Fraud alert delivered on both channels regardless of preference

**Given**: Cardholder contact with email + phone, `transactionNotificationsEnabled = false`

**When**: Publish `FraudAlertRaised` event

**Then**:
- 2 `NotificationRecord` rows (EMAIL + SMS) with status `SENT`
- Content includes "Suspicious activity" and triggered rule types
- Email gateway called once, SMS gateway called once
- (Preference opt-out is ignored for fraud alerts)

---

### Scenario 2.2 — Multiple triggered rules consolidated into one alert per channel

**Given**: Cardholder with email + phone registered

**When**: Publish `FraudAlertRaised` event with `triggeredRuleTypes = ["VELOCITY_EXCEEDED", "BLACKLISTED_CARD"]`

**Then**:
- Exactly 2 `NotificationRecord` rows (not 4)
- Each notification content includes both rule names

---

### Scenario 2.3 — Fraud alert with SMS only (no email registered)

**Given**: Cardholder with only phone (no email)

**When**: Publish `FraudAlertRaised` event

**Then**:
- Exactly 1 `NotificationRecord` row (SMS only)
- Email gateway not called

---

## User Story 3 Scenarios — Notification Preferences

### Scenario 3.1 — Register new contact → next event uses new address

**Given**: No existing contact for the card token

**When**: POST `/api/v1/cardholder-contacts` with email `new@example.com` and phone

**Then**:
- GET `/api/v1/cardholder-contacts/{cardToken}` returns the registered details
- Next `TransactionAuthorized` event for this token produces a notification to `new@example.com`

---

### Scenario 3.2 — Update email → next event uses new email

**Given**: Contact registered with `old@example.com`

**When**: PUT `/api/v1/cardholder-contacts/{cardToken}` with `email = "new@example.com"`

**Then**:
- Next event notification is sent to `new@example.com`
- `old@example.com` receives no further notifications

---

### Scenario 3.3 — Disable transaction notifications → only fraud alerts delivered

**Given**: Contact with email + phone, `transactionNotificationsEnabled = true`

**When**: PUT to update `transactionNotificationsEnabled = false`, then publish one `TransactionAuthorized` and one `FraudAlertRaised`

**Then**:
- 0 notification records from `TransactionAuthorized`
- 2 notification records from `FraudAlertRaised` (EMAIL + SMS)
