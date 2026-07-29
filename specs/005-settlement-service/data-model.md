# Data Model: Settlement Service

**Feature**: `005-settlement-service`
**Date**: 2026-06-13

---

## Entities

### 1. SettlementTransaction

Local copy of an authorized transaction, populated by consuming `TransactionAuthorizedEvent` from Kafka. Owned exclusively by the settlement-service schema.

**Table**: `settlement_transactions`

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `transaction_id` | `UUID` | PRIMARY KEY | Transaction identifier from originating service |
| `merchant_id` | `VARCHAR(100)` | NOT NULL | Merchant identifier for grouping |
| `card_token` | `VARCHAR(512)` | NOT NULL | Vault token (never raw PAN) |
| `masked_card_number` | `VARCHAR(19)` | NOT NULL | Display-safe `****-****-****-1234` |
| `amount` | `DECIMAL(15,2)` | NOT NULL, > 0 | Gross transaction amount |
| `currency_code` | `VARCHAR(3)` | NOT NULL, DEFAULT 'USD' | ISO 4217 currency code |
| `status` | `VARCHAR(20)` | NOT NULL, DEFAULT 'AUTHORIZED' | AUTHORIZED → SETTLED |
| `batch_id` | `VARCHAR(100)` | NULLABLE | Set when settled; references settlement_batches |
| `settlement_fee` | `DECIMAL(15,2)` | NULLABLE | Interchange fee deducted (set on SETTLED) |
| `net_amount` | `DECIMAL(15,2)` | NULLABLE | amount - settlement_fee (set on SETTLED) |
| `authorized_at` | `TIMESTAMPTZ` | NOT NULL | When the transaction was authorized |
| `settled_at` | `TIMESTAMPTZ` | NULLABLE | When this record was settled |
| `created_at` | `TIMESTAMPTZ` | NOT NULL, DEFAULT NOW() | When record was inserted by consumer |

**Indexes**:
- `idx_settlement_transactions_status` on `(status)` — batch reader filters by status
- `idx_settlement_transactions_merchant_batch` on `(merchant_id, batch_id)` — merchant report queries

**Status transitions**:
```
AUTHORIZED → SETTLED
```
No rollback; SETTLED is terminal. Already-SETTLED records are skipped by the batch processor.

**Validation rules**:
- `amount` MUST be > 0; zero-amount records inserted by the consumer are flagged and skipped during batch processing
- `card_token` MUST match vault token format (`vault:v1:...`); raw PAN rejected at consumer layer
- `merchant_id` MUST be non-null and non-empty; null-merchantId records are skipped with a WARNING log

---

### 2. SettlementBatch

One record per batch job execution. Provides an audit trail and links merchant settlements to a specific run.

**Table**: `settlement_batches`

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `batch_id` | `VARCHAR(100)` | PRIMARY KEY | UUID generated at job launch |
| `triggered_by` | `VARCHAR(20)` | NOT NULL | SCHEDULER or MANUAL |
| `status` | `VARCHAR(20)` | NOT NULL, DEFAULT 'RUNNING' | RUNNING → COMPLETED or FAILED |
| `total_transactions_processed` | `INT` | NOT NULL, DEFAULT 0 | Count of transactions settled (not skipped) |
| `total_gross_amount` | `DECIMAL(15,2)` | NOT NULL, DEFAULT 0.00 | Sum of gross amounts |
| `total_interchange_fees` | `DECIMAL(15,2)` | NOT NULL, DEFAULT 0.00 | Sum of interchange fees |
| `total_net_amount` | `DECIMAL(15,2)` | NOT NULL, DEFAULT 0.00 | Sum of net settlement amounts |
| `started_at` | `TIMESTAMPTZ` | NOT NULL, DEFAULT NOW() | Job start time |
| `completed_at` | `TIMESTAMPTZ` | NULLABLE | Job end time (null while running) |
| `failure_reason` | `TEXT` | NULLABLE | Error message if status = FAILED |

**Indexes**:
- `idx_settlement_batches_status` on `(status)` — query running batches
- `idx_settlement_batches_started_at` on `(started_at DESC)` — chronological audit queries

**Status transitions**:
```
RUNNING → COMPLETED (all chunks processed successfully)
RUNNING → FAILED (unrecoverable job-level error)
```

---

### 3. MerchantSettlement

Per-merchant aggregate for a batch run. Immutable once the batch completes. Queryable via REST API.

**Table**: `merchant_settlements`

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | `UUID` | PRIMARY KEY, generated | Surrogate key |
| `batch_id` | `VARCHAR(100)` | NOT NULL, FK → settlement_batches | Which batch produced this record |
| `merchant_id` | `VARCHAR(100)` | NOT NULL | Merchant being settled |
| `transaction_count` | `INT` | NOT NULL, > 0 | Number of transactions settled |
| `gross_amount` | `DECIMAL(15,2)` | NOT NULL | Sum of transaction amounts |
| `total_interchange_fee` | `DECIMAL(15,2)` | NOT NULL | Sum of interchange fees deducted |
| `net_settlement_amount` | `DECIMAL(15,2)` | NOT NULL | gross_amount - total_interchange_fee |
| `currency_code` | `VARCHAR(3)` | NOT NULL, DEFAULT 'USD' | Currency of settlement |
| `settlement_date` | `DATE` | NOT NULL | Calendar date of the settlement run |
| `created_at` | `TIMESTAMPTZ` | NOT NULL, DEFAULT NOW() | Record creation time |

**Constraints**:
- UNIQUE `(batch_id, merchant_id)` — one record per merchant per batch; enables UPSERT pattern for idempotent chunk replay

**Indexes**:
- `idx_merchant_settlements_merchant_batch` on `(merchant_id, batch_id)` — report lookup by merchant + batch
- `idx_merchant_settlements_date` on `(settlement_date DESC)` — date-range report queries

---

### 4. ProcessedEvent

Idempotency store for the Kafka consumer. Same pattern as all other services in this project.

**Table**: `processed_events`

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `event_key` | `VARCHAR(255)` | PRIMARY KEY | `settlement:TransactionAuthorized:{eventId}` |
| `processed_at` | `TIMESTAMPTZ` | NOT NULL, DEFAULT NOW() | When first processed |

---

## Spring Batch Metadata Tables (V1)

Spring Batch requires its own schema for job/step execution tracking. These are standard Spring Batch 5.x tables using CockroachDB-compatible PostgreSQL DDL:

`BATCH_JOB_SEQ`, `BATCH_JOB_EXECUTION_SEQ`, `BATCH_STEP_EXECUTION_SEQ` (sequences), `BATCH_JOB_INSTANCE`, `BATCH_JOB_EXECUTION`, `BATCH_JOB_EXECUTION_PARAMS`, `BATCH_JOB_EXECUTION_CONTEXT`, `BATCH_STEP_EXECUTION`, `BATCH_STEP_EXECUTION_CONTEXT`

These are included verbatim from Spring Batch's `schema-postgresql.sql` in `V1__create_spring_batch_schema.sql`.

---

## Entity Relationships

```
ProcessedEvent (standalone — idempotency only)

SettlementBatch ──< MerchantSettlement (one batch → many merchant records)
                 ──< SettlementTransaction (one batch → many settled transactions, via batch_id FK)

SettlementTransaction  →  SettlementBatch (batch_id, set on settle)
MerchantSettlement     →  SettlementBatch (batch_id)
```

---

## State Machine: SettlementTransaction.status

```
[Consumer receives TransactionAuthorizedEvent]
        ↓
  AUTHORIZED  ──[batch processes]──→  SETTLED (terminal)
              ──[batch skips/re-runs]──→  AUTHORIZED (unchanged)
```

---

## JPA Entity Design Notes

- **`SettlementTransaction`**: `@Id UUID transactionId` (not generated — assigned from Kafka event). `@PrePersist` sets `createdAt`.
- **`SettlementBatch`**: `@Id String batchId` (UUID string, generated by `SettlementService`). No JPA `@GeneratedValue` — ID is assigned before save.
- **`MerchantSettlement`**: `@Id @GeneratedValue(strategy = UUID) UUID id`. Unique constraint on `(batchId, merchantId)`.
- **`ProcessedEvent`**: `@Id String eventKey`. Constructor sets `processedAt`. No `@PrePersist` (native query bypasses JPA lifecycle — same pattern as notification-service).

---

## Flyway Migration Order

| Version | File | Description |
|---------|------|-------------|
| V1 | `V1__create_spring_batch_schema.sql` | Spring Batch 5.x metadata tables (PostgreSQL-compatible) |
| V2 | `V2__create_settlement_transactions.sql` | settlement_transactions table + indexes |
| V3 | `V3__create_settlement_batches.sql` | settlement_batches table + indexes |
| V4 | `V4__create_merchant_settlements.sql` | merchant_settlements table + unique constraint + indexes |
| V5 | `V5__create_processed_events.sql` | processed_events table (consumer idempotency) |
