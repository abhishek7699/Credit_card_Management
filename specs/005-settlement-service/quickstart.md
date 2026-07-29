# Quickstart & Test Scenarios: Settlement Service

**Feature**: `005-settlement-service`
**Date**: 2026-06-13

---

## Prerequisites for All Scenarios

- CockroachDB (Testcontainers PostgreSQL) with Flyway migrations V1–V5 applied
- `@EmbeddedKafka(topics = {"transaction.authorized", "transaction.settled"})`
- `@ActiveProfiles("test")`
- `@DirtiesContext` on every integration test class (Spring Batch JobRepository state)
- Interchange fee rate: 2.0% (`settlement.interchange-fee-rate: 0.02` in `application-test.yml`)

---

## User Story 1: End-of-Day Batch Settlement

### Scenario 1.1 — Happy Path: Multiple Merchants

**Setup**: Publish 5 `TransactionAuthorizedEvent` to `transaction.authorized`:
- M1: tx1 $100.00, tx2 $200.00
- M2: tx3 $150.00, tx4 $50.00
- M3: tx5 $300.00

**Trigger**: Call `POST /internal/settlement/trigger` (or launch job directly via `JobLauncherTestUtils`)

**Assert**:
- All 5 rows in `settlement_transactions` have `status = SETTLED`
- `merchant_settlements` has 3 rows (M1, M2, M3)
- M1: `transaction_count=2, gross_amount=300.00, total_interchange_fee=6.00, net_settlement_amount=294.00`
- M2: `transaction_count=2, gross_amount=200.00, total_interchange_fee=4.00, net_settlement_amount=196.00`
- M3: `transaction_count=1, gross_amount=300.00, total_interchange_fee=6.00, net_settlement_amount=294.00`
- `settlement_batches` has 1 row with `status=COMPLETED, total_transactions_processed=5`

---

### Scenario 1.2 — Zero AUTHORIZED Transactions

**Setup**: No events published to Kafka; no rows in `settlement_transactions`

**Trigger**: Launch settlement job

**Assert**:
- Job completes with `status=COMPLETED`
- `settlement_batches` has 1 row with `total_transactions_processed=0`
- `merchant_settlements` is empty
- No `TransactionSettledEvent` published to `transaction.settled`

---

### Scenario 1.3 — Idempotent Re-run: Already Settled Transactions Skipped

**Setup**: Publish 3 `TransactionAuthorizedEvent`, run the batch → all 3 SETTLED

**Action**: Trigger the batch again

**Assert**:
- `settlement_transactions` still has 3 rows, all `status=SETTLED`
- `merchant_settlements` has the SAME values as after the first run (UPSERT is idempotent)
- `settlement_batches` has 2 rows (new batch ID per run), both `status=COMPLETED`
- Second batch: `total_transactions_processed=0` (all skipped as already settled)
- No additional `TransactionSettledEvent` published in the second run

---

### Scenario 1.4 — Null Merchant ID Transactions Skipped

**Setup**: Publish 3 events — 2 with valid merchantId, 1 with `merchantId=null`

**Trigger**: Launch batch

**Assert**:
- 2 rows settled; 1 row with null merchantId remains AUTHORIZED
- `merchant_settlements` has 2 rows for the valid merchants
- WARN log entry for the null-merchantId skip
- Batch completes with `status=COMPLETED`

---

### Scenario 1.5 — Zero-Amount Transaction Skipped

**Setup**: Publish 2 events — 1 with `amount=150.00`, 1 with `amount=0.00`

**Trigger**: Launch batch

**Assert**:
- 1 row settled (the $150 one)
- Zero-amount record not in `settlement_transactions` (skipped at consumer; if inserted, batch processor also skips it)
- `settlement_batches` has `total_transactions_processed=1`

---

### Scenario 1.6 — Partial Batch Failure: One Merchant Fails, Others Succeed

**Setup**: Seed 3 AUTHORIZED transactions directly in `settlement_transactions` (bypassing Kafka consumer):
- M1: tx1 $100 (will succeed)
- M2: tx2 $200 (configured to throw RuntimeException in writer)
- M3: tx3 $300 (will succeed)

**Trigger**: Launch batch with skip configured for RuntimeException (or use a test skip policy)

**Assert**:
- M1 and M3 settled; M2 remains AUTHORIZED
- `merchant_settlements` has rows for M1 and M3, not M2
- Batch completes with `status=COMPLETED` (not FAILED — skip policy absorbed M2 failure)

---

## User Story 2: Settlement Event Publishing

### Scenario 2.1 — One Event Per Settled Transaction

**Setup**: Publish 5 `TransactionAuthorizedEvent`, wait for consumer to populate `settlement_transactions`

**Trigger**: Launch batch

**Assert (Kafka consumer on `transaction.settled` topic)**:
- Exactly 5 `TransactionSettledEvent` messages received
- Each event has: `transactionId`, `merchantId`, `batchId`, `grossAmount`, `interchangeFee`, `netAmount`, `settlementDate`, `settledAt`
- `interchangeFee = grossAmount × 0.02` (HALF_UP to 2dp)
- `netAmount = grossAmount - interchangeFee`

---

### Scenario 2.2 — No Duplicate Events on Re-run

**Setup**: 3 transactions settled in first run → 3 events published

**Action**: Trigger second batch run

**Assert**:
- No additional events published to `transaction.settled` in the second run
- Total event count on topic remains 3

---

### Scenario 2.3 — Failed Settlement Produces No Event

**Setup**: Inject a mock that throws `RuntimeException` during DB write for tx1; batch skips it

**Assert**:
- No `TransactionSettledEvent` published for tx1
- Events published only for successfully settled transactions

---

## User Story 3: Merchant Settlement Report

### Scenario 3.1 — Report Available After Batch

**Setup**: Publish 3 events for M1, trigger batch, await completion

**Action**: `GET /internal/settlement/reports/M1/{batchId}`

**Assert**:
- HTTP 200
- `transactionCount=3`
- `grossAmount` = sum of 3 amounts
- `totalInterchangeFee` = sum × 0.02 (rounded per transaction, then summed)
- `netSettlementAmount = grossAmount - totalInterchangeFee`
- `settlementDate` = today (UTC)
- `batchId` matches the trigger response

---

### Scenario 3.2 — Report Per Merchant, Not Cross-Merchant

**Setup**: 2 merchants M1 (2 tx), M2 (3 tx) in one batch

**Action**: `GET /internal/settlement/reports/M1/{batchId}`

**Assert**: Response contains only M1's data (2 transactions), not M2's

---

### Scenario 3.3 — 404 for Unknown Merchant/Batch Combination

**Action**: `GET /internal/settlement/reports/unknown-merchant/nonexistent-batch`

**Assert**: HTTP 404 with `{"error": "No settlement report found for merchantId=unknown-merchant and batchId=nonexistent-batch"}`

---

### Scenario 3.4 — Report Immutable After Re-run

**Setup**: Run batch → get report for M1 with values (count=5, gross=1000)
**Action**: Re-run batch (idempotent — same 5 transactions already settled)
**Assert**: Report for M1 and original batchId unchanged (count=5, gross=1000). Second batch's report shows count=0.

---

## Consumer Idempotency Scenarios

### Scenario C.1 — Duplicate TransactionAuthorizedEvent Ignored

**Setup**: Publish same `TransactionAuthorizedEvent` (same `eventId`) 5 times

**Assert**:
- `settlement_transactions` has exactly 1 row for that transactionId
- `processed_events` has exactly 1 row for `settlement:TransactionAuthorized:{eventId}`

---

### Scenario C.2 — Null EventId Discarded

**Setup**: Publish `TransactionAuthorizedEvent` with `eventId=null`

**Assert**:
- No row inserted in `settlement_transactions`
- No row inserted in `processed_events`
- WARN log entry

---

## Scheduled Trigger Scenario

### Scenario S.1 — Scheduler Fires at Configured Cron

**Test approach**: Unit test `SettlementScheduler` with a mocked `SettlementService`. Verify `SettlementService.launchJob()` is called when the scheduled method is invoked. Integration test with short cron interval not required (rely on unit test for scheduling logic).

---

## Batch Status Scenario

### Scenario B.1 — Concurrent Trigger Rejected

**Setup**: One batch job running (status=RUNNING in `settlement_batches`)

**Action**: `POST /internal/settlement/trigger`

**Assert**: HTTP 409 with `{"error": "Settlement batch is already running", "runningBatchId": "..."}`

---

## Interchange Fee Accuracy Verification

| grossAmount | rate | interchangeFee (HALF_UP 2dp) | netAmount |
|-------------|------|------------------------------|-----------|
| 100.00 | 0.02 | 2.00 | 98.00 |
| 150.75 | 0.02 | 3.02 (150.75×0.02=3.015→3.02) | 147.73 |
| 0.01 | 0.02 | 0.00 (0.01×0.02=0.0002→0.00) | 0.01 |
| 10000.00 | 0.02 | 200.00 | 9800.00 |

These values must be verified in `InterchangeFeeServiceTest` unit tests.
