# Data Model: Reporting Service API

The reporting-service is read-only. It does NOT own any schema and runs no Flyway migrations in production. All entities below map to tables created and owned by the settlement-service. The reporting-service accesses them via a read-only DB role.

---

## Entity: SettlementTransaction (read-only)

**Source table**: `settlement_transactions` (owned by settlement-service)

| Column | Type | Notes |
|--------|------|-------|
| `transaction_id` | UUID PK | Immutable primary key |
| `merchant_id` | VARCHAR(100) | Merchant identifier — filter target |
| `card_token` | VARCHAR(512) | Opaque token — filter target; never a raw PAN |
| `masked_card_number` | VARCHAR(19) | Display-safe masked number (e.g., 4111-****-****-1111) |
| `amount` | DECIMAL(15,2) | Original transaction amount |
| `currency_code` | CHAR(3) | ISO 4217 code (default USD) |
| `status` | VARCHAR(20) | AUTHORIZED / SETTLED — filter target |
| `batch_id` | VARCHAR(100) | Populated after settlement; null for AUTHORIZED |
| `settlement_fee` | DECIMAL(15,2) | Populated after settlement; null for AUTHORIZED |
| `net_amount` | DECIMAL(15,2) | Populated after settlement; null for AUTHORIZED |
| `authorized_at` | TIMESTAMPTZ | Always present — primary sort + date range filter |
| `settled_at` | TIMESTAMPTZ | Populated after settlement; null for AUTHORIZED |
| `created_at` | TIMESTAMPTZ | Record creation timestamp |

**Existing indices**: `status` (for status filter), `(merchant_id, batch_id)` (for merchant-batch queries)

**Filtering rules**:
- `cardToken` → WHERE card_token = :cardToken
- `merchantId` → WHERE merchant_id = :merchantId
- `status` → WHERE status = :status (must be one of: AUTHORIZED, SETTLED)
- `from` → WHERE authorized_at >= :from
- `to` → WHERE authorized_at <= :to
- Default sort: `authorized_at DESC`
- Max date range: 90 days (enforced in service layer before query execution)

---

## Entity: MerchantSettlement (read-only)

**Source table**: `merchant_settlements` (owned by settlement-service)

| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | Generated UUID |
| `batch_id` | VARCHAR(100) | Foreign reference to settlement_batches |
| `merchant_id` | VARCHAR(100) | Merchant identifier |
| `transaction_count` | INT | Pre-aggregated count for this batch |
| `gross_amount` | DECIMAL(15,2) | Pre-aggregated gross for this batch |
| `total_interchange_fee` | DECIMAL(15,2) | Pre-aggregated fees for this batch |
| `net_settlement_amount` | DECIMAL(15,2) | Pre-aggregated net for this batch |
| `currency_code` | CHAR(3) | ISO 4217 code (default USD) |
| `settlement_date` | DATE | Date the settlement batch ran |
| `created_at` | TIMESTAMPTZ | Record creation timestamp |

**Unique constraint**: `(batch_id, merchant_id)`

**Existing indices**: `(merchant_id, batch_id)`, `settlement_date DESC`

**Filtering rules**:
- `merchantId` → WHERE merchant_id = :merchantId
- `from`/`to` → WHERE settlement_date BETWEEN :from AND :to
- `batchId` → WHERE batch_id = :batchId
- For merchant summary across dates: GROUP BY merchant_id, SUM aggregates (done in service layer after fetching records, or via `@Query`)

---

## Entity: SettlementBatch (read-only)

**Source table**: `settlement_batches` (owned by settlement-service)

| Column | Type | Notes |
|--------|------|-------|
| `batch_id` | VARCHAR(100) PK | The batch identifier |
| `triggered_by` | VARCHAR(20) | MANUAL / SCHEDULER |
| `status` | VARCHAR(20) | RUNNING / COMPLETED / FAILED |
| `total_transactions_processed` | INT | Batch-level aggregate |
| `total_gross_amount` | DECIMAL(15,2) | Batch-level aggregate |
| `total_interchange_fees` | DECIMAL(15,2) | Batch-level aggregate |
| `total_net_amount` | DECIMAL(15,2) | Batch-level aggregate |
| `started_at` | TIMESTAMPTZ | Batch start time |
| `completed_at` | TIMESTAMPTZ | Batch completion time (null if RUNNING) |
| `failure_reason` | TEXT | Error message for FAILED batches |

**Existing indices**: `status`, `started_at DESC`

---

## Response DTOs

These are not persisted entities — they are the API response shapes.

### TransactionResponse
Maps `SettlementTransaction` to API response. Fields: `transactionId`, `cardToken`, `maskedCardNumber`, `merchantId`, `amount`, `currencyCode`, `status`, `batchId`, `settlementFee`, `netAmount`, `authorizedAt`, `settledAt`.

### PagedResponse<T>
Wraps paginated list results. Fields: `content` (list), `page`, `size`, `totalElements`, `totalPages`.

### MerchantSummaryResponse
Aggregated view per merchant. Fields: `merchantId`, `transactionCount`, `grossAmount`, `totalInterchangeFee`, `netSettlementAmount`, `currencyCode`, `batchCount` (how many batches), `from`, `to`.

### SettlementBatchResponse
Maps `SettlementBatch`. Fields: `batchId`, `status`, `triggeredBy`, `totalTransactionsProcessed`, `totalGrossAmount`, `totalInterchangeFees`, `totalNetAmount`, `startedAt`, `completedAt`, `failureReason`.

### MerchantSettlementResponse
Maps `MerchantSettlement`. Fields: `id`, `batchId`, `merchantId`, `transactionCount`, `grossAmount`, `totalInterchangeFee`, `netSettlementAmount`, `currencyCode`, `settlementDate`.
