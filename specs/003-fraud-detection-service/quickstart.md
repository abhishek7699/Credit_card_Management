# Quickstart: Fraud Detection Service Integration Scenarios

**Date**: 2026-06-12
**Feature**: [spec.md](./spec.md) | **Data Model**: [data-model.md](./data-model.md)

These scenarios drive integration tests in `FraudDetectionIntegrationTest` and
`IdempotencyIntegrationTest`. Each scenario is independently testable via
`@EmbeddedKafka` + Testcontainers PostgreSQL.

---

## User Story 1: Suspicious Transaction Detection (P1)

### Scenario 1.1 — Velocity Rule Triggers

**Setup**: Seed `fraud_rule_configs` with VELOCITY_EXCEEDED enabled,
`{"maxTransactions": 3, "windowMinutes": 10}`. Seed `transaction_records` with 3 records
for card token `vault:v1:A` all within the last 5 minutes.

**Action**: Publish a `TransactionInitiated` event to `transaction.initiated` for card
`vault:v1:A`.

**Assert**:
- `fraud_alerts` table contains exactly 1 row with `card_token = 'vault:v1:A'` and
  `triggered_rules` containing `'VELOCITY_EXCEEDED'`.
- Topic `fraud.alert.raised` receives exactly 1 message with `triggeredRuleTypes` = `["VELOCITY_EXCEEDED"]`.
- `transaction_records` has 1 new row for this transaction's `transactionId`.
- `processed_events` has 1 new row for key `fraud:TransactionInitiated:{eventId}`.

### Scenario 1.2 — Amount Anomaly Rule Triggers

**Setup**: Seed AMOUNT_ANOMALY config with `{"multiplier": 3.0, "lookbackDays": 30, "absoluteCeiling": 10000.00}`.
Seed `transaction_records` with 5 records for card `vault:v1:B`, each with `amount = 100.00`
within the last 30 days.

**Action**: Publish event with `amount = 350.00` (> 3.0 × 100.00 average) for card `vault:v1:B`.

**Assert**:
- 1 fraud alert with `triggered_rules` containing `'AMOUNT_ANOMALY'`.
- `fraud.alert.raised` receives 1 message with `triggeredRuleTypes` = `["AMOUNT_ANOMALY"]`.

### Scenario 1.3 — Blacklist Rule Triggers

**Setup**: Insert `vault:v1:C` into `blacklist_entries`. Seed BLACKLISTED_CARD config as enabled.

**Action**: Publish event for card `vault:v1:C`.

**Assert**:
- 1 fraud alert with `triggered_rules` containing `'BLACKLISTED_CARD'`.
- `fraud.alert.raised` receives 1 message with `triggeredRuleTypes` = `["BLACKLISTED_CARD"]`.

### Scenario 1.4 — Clean Transaction (No Rules Trigger)

**Setup**: Seed all 3 rule configs as enabled with default thresholds. Card `vault:v1:D`
has no transaction history and is not blacklisted.

**Action**: Publish event for `vault:v1:D` with `amount = 50.00`.

**Assert**:
- `fraud_alerts` table contains 0 rows for card `vault:v1:D`.
- `fraud.alert.raised` topic receives 0 messages.
- `transaction_records` has 1 row (history written for all processed transactions).
- `processed_events` has 1 row for the event key.

### Scenario 1.5 — Multiple Rules Trigger Simultaneously

**Setup**: Card `vault:v1:E` is blacklisted AND has exceeded velocity threshold.

**Action**: Publish event for `vault:v1:E`.

**Assert**:
- Exactly 1 fraud alert row (not 2).
- `triggered_rules` contains BOTH `'BLACKLISTED_CARD'` and `'VELOCITY_EXCEEDED'`.
- `fraud.alert.raised` receives exactly 1 message with 2 entries in `triggeredRuleTypes`.

---

## User Story 2: Configurable Fraud Rules (P2)

### Scenario 2.1 — Threshold Update Takes Effect Without Restart

**Setup**: Seed VELOCITY_EXCEEDED config with `{"maxTransactions": 5, "windowMinutes": 10}`.
Card `vault:v1:F` has 4 existing transaction records within the window.

**Action (round 1)**: Publish event for `vault:v1:F`. Assert no alert raised (4 < 5).

**Update**: Update `fraud_rule_configs` set `parameters = '{"maxTransactions": 3, "windowMinutes": 10}'`
for `rule_type = 'VELOCITY_EXCEEDED'`.

**Action (round 2)**: Publish a second event for `vault:v1:F` (now 5 records ≥ new threshold of 3).

**Assert (round 2)**:
- 1 fraud alert for `vault:v1:F` with `VELOCITY_EXCEEDED`.
- No service restart occurred between round 1 and round 2.

### Scenario 2.2 — Blacklist Entry Addition

**Setup**: No blacklist entries. Card `vault:v1:G` is not blacklisted.

**Action (round 1)**: Publish event for `vault:v1:G`. Assert no BLACKLISTED_CARD alert.

**Update**: Insert `vault:v1:G` into `blacklist_entries`.

**Action (round 2)**: Publish event for `vault:v1:G` (new eventId).

**Assert (round 2)**: 1 fraud alert with `BLACKLISTED_CARD` triggered.

### Scenario 2.3 — New Card with No History Uses Absolute Ceiling

**Setup**: AMOUNT_ANOMALY config: `{"multiplier": 1.0, "lookbackDays": 30, "absoluteCeiling": 500.00}`.
Card `vault:v1:H` has zero transaction history.

**Action**: Publish event with `amount = 600.00`.

**Assert**: 1 fraud alert with `AMOUNT_ANOMALY` (600 > 1.0 × 500 ceiling).

**Action**: Publish event with `amount = 400.00` (new eventId, new transactionId).

**Assert**: No alert (400 ≤ 1.0 × 500 ceiling).

---

## User Story 3: Exactly-Once Processing (P3)

### Scenario 3.1 — Duplicate Suspicious Event Produces One Alert

**Setup**: Single suspicious `TransactionInitiated` event payload (velocity threshold
exceeded). Same `eventId` used in all deliveries.

**Action**: Publish the same event 10 times to `transaction.initiated`.

**Assert**:
- `fraud_alerts` contains exactly 1 row for the `transactionId`.
- `fraud.alert.raised` contains at most 1 message for the `transactionId`.
- `processed_events` contains exactly 1 row for the `eventId`.
- `transaction_records` contains exactly 1 row for the `transactionId`.

### Scenario 3.2 — Duplicate Clean Event Produces No Alert

**Setup**: Single clean event (no rules triggered). Same `eventId` used twice.

**Action**: Publish the same event twice.

**Assert**:
- `fraud_alerts` contains 0 rows for the `transactionId`.
- `fraud.alert.raised` receives 0 messages.
- `processed_events` contains exactly 1 row.

### Scenario 3.3 — Malformed Event Is Discarded (Edge Case)

**Setup**: Send a `ConsumerRecord` with a null value payload.

**Assert**:
- No exception propagates; listener acknowledges offset.
- `fraud_alerts` contains 0 new rows.
- `fraud.alert.raised` receives 0 messages.

---

## Local Development Quickstart

```bash
# Start infrastructure
docker-compose up -d cockroachdb kafka

# Run all tests (unit + integration + coverage gate)
./gradlew :services:fraud-detection-service:test \
          :services:fraud-detection-service:jacocoTestReport \
          :services:fraud-detection-service:jacocoTestCoverageVerification

# Run service locally
./gradlew :services:fraud-detection-service:bootRun --args='--spring.profiles.active=local'
```
