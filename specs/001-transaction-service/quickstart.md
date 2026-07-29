# Quickstart: Transaction Service (Local Development)

**Prerequisites**: Docker Desktop, Java 17, Gradle 8.x

---

## 1. Start Local Infrastructure

From the repository root, bring up all infrastructure dependencies:

```bash
docker-compose up -d cockroachdb kafka vault redis
```

This starts:
- **CockroachDB**: `localhost:26257` (SQL), `localhost:8080` (Admin UI)
- **Kafka** (KRaft mode): `localhost:9092`
- **HashiCorp Vault** (dev mode): `localhost:8200`
- **Redis**: `localhost:6379`

Wait for all services to report healthy:
```bash
docker-compose ps
# All services should show "healthy" or "running"
```

---

## 2. Initialize HashiCorp Vault

Run the initialization script once after Vault starts (only needed on first run):

```bash
bash scripts/vault-init.sh
```

This script:
1. Enables the Transit Secrets Engine: `vault secrets enable transit`
2. Creates the card PAN encryption key: `vault write -f transit/keys/card-pan-key`

Verify Vault is ready:
```bash
curl http://localhost:8200/v1/sys/health
# Expected: {"initialized":true,"sealed":false,"standby":false,...}
```

---

## 3. Initialize the Database Schema

Run the schema migration to create `transactions` and `processed_events` tables:

```bash
./gradlew :services:transaction-service:flywayMigrate \
  -Pflyway.url=jdbc:postgresql://localhost:26257/defaultdb?sslmode=disable \
  -Pflyway.user=root \
  -Pflyway.password=
```

Or start the service in local profile and it will auto-migrate on startup (Flyway is
configured to run migrations automatically in `application-local.yml`).

---

## 4. Run the Service

```bash
./gradlew :services:transaction-service:bootRun --args='--spring.profiles.active=local'
```

The service starts with:
- **gRPC server** on port `9090`
- **Management/health** endpoints on port `8081` (actuator)

Verify the service is up:
```bash
curl http://localhost:8081/actuator/health
# Expected: {"status":"UP"}
```

---

## 5. Test: Initiate a Payment

Use `grpcurl` to send a payment request:

```bash
# Install grpcurl if needed: brew install grpcurl

grpcurl -plaintext \
  -proto proto/v1/transaction_service.proto \
  -d '{
    "card_number": "4111111111111111",
    "card_expiry": "12/26",
    "cvv": "123",
    "cardholder_name": "Jane Smith",
    "amount": "99.99",
    "currency_code": "USD"
  }' \
  localhost:9090 transaction.v1.TransactionService/InitiateTransaction
```

**Expected response**:
```json
{
  "transaction_id": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "status": "PENDING"
}
```

---

## 6. Verify Security Invariants

Confirm the raw card number is NOT in the database:

```bash
# Connect to CockroachDB
docker exec -it cockroachdb ./cockroach sql --insecure
```

```sql
-- Must return the card token (vault:v1:...) NOT the raw PAN
SELECT id, card_token, masked_card_number, status FROM transactions LIMIT 5;

-- card_token should look like: vault:v1:8SDd3WHDOjf7mq69CyCqYjBXA...
-- masked_card_number should look like: ****-****-****-1111
-- NO row should contain 4111111111111111
```

Confirm the card number is NOT in the logs:

```bash
docker logs transaction-service 2>&1 | grep -E "411111|card_number"
# Must return no results
```

---

## 7. Retrieve Transaction Status

```bash
grpcurl -plaintext \
  -proto proto/v1/transaction_service.proto \
  -d '{"transaction_id": "7c9e6679-7425-40de-944b-e07fc1f90ae7"}' \
  localhost:9090 transaction.v1.TransactionService/GetTransaction
```

---

## 8. Run the Test Suite

```bash
# All tests (unit + integration + contract)
./gradlew :services:transaction-service:test

# Test report
open services/transaction-service/build/reports/tests/test/index.html

# Coverage report — must show 100%
open services/transaction-service/build/reports/jacoco/test/html/index.html
```

The build FAILS if coverage drops below 100% (Principle II / SC-004).

---

## 9. Shutdown

```bash
docker-compose down
```

To wipe all data (including CockroachDB volumes):
```bash
docker-compose down -v
```

---

## Troubleshooting

| Symptom | Likely Cause | Fix |
|---------|-------------|-----|
| Service fails to start — Vault connection refused | Vault not running or not initialized | Run `docker-compose up -d vault` then `bash scripts/vault-init.sh` |
| `SQLSTATE 40001` errors in logs | CockroachDB serialization conflict | Expected under high concurrency; Spring Retry handles this transparently |
| `grpcurl` returns `StatusRuntimeException: UNAVAILABLE` | Service not started | Check `curl http://localhost:8081/actuator/health` |
| Coverage report < 100% | Missing tests | Write the missing test, confirm it FAILS, then implement the fix |
| `card_number found in logs` | PAN leaked in log statement | Immediate security incident — fix before any other work proceeds |
