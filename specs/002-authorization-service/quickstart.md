# Quickstart: Authorization Service (Local Development)

**Prerequisites**: Docker Desktop, Java 17, Gradle 8.x, `kcat` or `kafka-console-producer`

---

## 1. Start Local Infrastructure

The authorization-service requires CockroachDB, Kafka, and (optionally) Vault.
From the repository root:

```bash
docker-compose up -d cockroachdb kafka
```

Wait for services to be healthy:
```bash
docker-compose ps
# cockroachdb and kafka should show "healthy"
```

---

## 2. Start the Service

```bash
./gradlew :services:authorization-service:bootRun --args='--spring.profiles.active=local'
```

The service starts with:
- **Management/health** endpoint on port `8082` (actuator)
- **Kafka consumer** listening on `transaction.initiated`

Verify it is up:
```bash
curl http://localhost:8082/actuator/health
# Expected: {"status":"UP"}
```

---

## 3. Seed a Card Account

The authorization-service must have a `card_accounts` row for the card token you will use
in tests. Flyway runs automatically on startup and creates the schema. Insert a test account:

```bash
docker exec -it credit-card-cockroachdb-1 \
  cockroach sql --insecure --execute "
    INSERT INTO authorization.card_accounts
      (id, card_token, status, credit_limit, available_credit, currency_code)
    VALUES
      (gen_random_uuid(),
       'vault:v1:TEST_TOKEN_ACTIVE',
       'ACTIVE',
       5000.00,
       5000.00,
       'USD');
  "
```

---

## 4. Integration Scenario 1 — Authorized Transaction

Publish a `TransactionInitiated` event for the seeded card with a valid amount:

```bash
echo '{
  "eventId":      "aaaaaaaa-0000-0000-0000-000000000001",
  "eventType":    "TransactionInitiated",
  "sourceService":"transaction-service",
  "traceId":      "test-trace-001",
  "occurredAt":   "2026-06-11T10:00:00Z",
  "payload": {
    "transactionId":    "bbbbbbbb-0000-0000-0000-000000000001",
    "cardToken":        "vault:v1:TEST_TOKEN_ACTIVE",
    "maskedCardNumber": "****-****-****-1111",
    "cardholderName":   "Test User",
    "amount":           "100.00",
    "currencyCode":     "USD"
  }
}' | kafka-console-producer \
    --bootstrap-server localhost:9092 \
    --topic transaction.initiated \
    --property "parse.key=true" \
    --property "key.separator=|" \
    --message-key "bbbbbbbb-0000-0000-0000-000000000001"
```

**Verify in `transaction.status.updated`**:
```bash
kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic transaction.status.updated \
  --from-beginning --max-messages 1
# Expected eventType: "TransactionAuthorized"
# Expected payload.transactionId: "bbbbbbbb-0000-0000-0000-000000000001"
```

**Verify credit deduction in DB**:
```bash
docker exec -it credit-card-cockroachdb-1 \
  cockroach sql --insecure --execute "
    SELECT available_credit FROM authorization.card_accounts
    WHERE card_token = 'vault:v1:TEST_TOKEN_ACTIVE';
  "
# Expected: 4900.00 (5000.00 - 100.00)
```

**Verify authorization record persisted**:
```bash
docker exec -it credit-card-cockroachdb-1 \
  cockroach sql --insecure --execute "
    SELECT transaction_id, outcome, reason_code FROM authorization.authorization_records
    WHERE transaction_id = 'bbbbbbbb-0000-0000-0000-000000000001';
  "
# Expected: outcome=AUTHORIZED, reason_code=null
```

---

## 5. Integration Scenario 2 — Declined: Insufficient Credit

Attempt an authorization for an amount exceeding available credit:

```bash
echo '{
  "eventId":      "aaaaaaaa-0000-0000-0000-000000000002",
  "eventType":    "TransactionInitiated",
  "sourceService":"transaction-service",
  "traceId":      "test-trace-002",
  "occurredAt":   "2026-06-11T10:01:00Z",
  "payload": {
    "transactionId":    "bbbbbbbb-0000-0000-0000-000000000002",
    "cardToken":        "vault:v1:TEST_TOKEN_ACTIVE",
    "maskedCardNumber": "****-****-****-1111",
    "cardholderName":   "Test User",
    "amount":           "99999.00",
    "currencyCode":     "USD"
  }
}' | kafka-console-producer \
    --bootstrap-server localhost:9092 \
    --topic transaction.initiated \
    --property "parse.key=true" \
    --property "key.separator=|" \
    --message-key "bbbbbbbb-0000-0000-0000-000000000002"
```

**Expected result**:
- `transaction.status.updated` receives `TransactionDeclined` with
  `payload.reasonCode = "INSUFFICIENT_CREDIT"`
- `available_credit` in DB is unchanged
- `authorization_records` has `outcome=DECLINED, reason_code=INSUFFICIENT_CREDIT`

---

## 6. Integration Scenario 3 — Declined: Invalid Card

Submit a transaction for a card token not in the registry:

```bash
echo '{
  "eventId":      "aaaaaaaa-0000-0000-0000-000000000003",
  "eventType":    "TransactionInitiated",
  "sourceService":"transaction-service",
  "traceId":      "test-trace-003",
  "occurredAt":   "2026-06-11T10:02:00Z",
  "payload": {
    "transactionId":    "bbbbbbbb-0000-0000-0000-000000000003",
    "cardToken":        "vault:v1:UNKNOWN_TOKEN",
    "maskedCardNumber": "****-****-****-9999",
    "cardholderName":   "Unknown",
    "amount":           "50.00",
    "currencyCode":     "USD"
  }
}' | kafka-console-producer \
    --bootstrap-server localhost:9092 \
    --topic transaction.initiated
```

**Expected result**:
- `TransactionDeclined` with `payload.reasonCode = "INVALID_CARD"`

---

## 7. Integration Scenario 4 — Idempotency

Publish the same event from Scenario 1 a second time (same `eventId`):

```bash
# Re-publish the exact same JSON as Scenario 1 (eventId unchanged)
```

**Expected result**:
- No new record in `authorization_records`
- `available_credit` still 4900.00 (not decremented again)
- No new event published to `transaction.status.updated`
- Service log shows: idempotent skip for `auth:TransactionInitiated:aaaaaaaa-0000-0000-0000-000000000001`

---

## 8. Run the Full Test Suite

```bash
./gradlew :services:authorization-service:test jacocoTestReport jacocoTestCoverageVerification
```

All tests should pass and the JaCoCo 100% coverage gate should be satisfied.
