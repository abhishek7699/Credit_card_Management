# Quickstart & Integration Test Scenarios: Reporting Service API

These scenarios drive the TDD integration tests. Each scenario is independently testable using Testcontainers (PostgreSQL) + `@SpringBootTest(webEnvironment=RANDOM_PORT)` + `TestRestTemplate`.

Test setup: All integration tests pre-populate the database via `@Sql` scripts that create the settlement schema tables and insert fixture data.

---

## US1: Transaction History (Priority: P1)

### Scenario 1.1 — Filter by card token returns only that card's transactions

**Setup**: Insert 3 transactions — 2 for `tok-card-A`, 1 for `tok-card-B`. All status=SETTLED.

**Request**: `GET /internal/reporting/transactions?cardToken=tok-card-A&size=10`

**Expected**:
- HTTP 200
- `content` has 2 entries
- Both entries have `cardToken = "tok-card-A"`
- Neither entry has `cardToken = "tok-card-B"`
- `totalElements = 2`

---

### Scenario 1.2 — Filter by merchant ID returns only that merchant's transactions

**Setup**: Insert 4 transactions — 2 for `merchant-001`, 2 for `merchant-002`. Mix of AUTHORIZED and SETTLED.

**Request**: `GET /internal/reporting/transactions?merchantId=merchant-001`

**Expected**:
- HTTP 200
- `content` has 2 entries, all with `merchantId = "merchant-001"`
- `totalElements = 2`

---

### Scenario 1.3 — Filter by date range returns transactions within window

**Setup**: Insert 3 transactions with `authorized_at` values: 2026-01-01, 2026-03-15, 2026-06-01.

**Request**: `GET /internal/reporting/transactions?from=2026-02-01T00:00:00Z&to=2026-05-01T00:00:00Z`

**Expected**:
- HTTP 200
- Only the 2026-03-15 transaction is returned (`totalElements = 1`)

---

### Scenario 1.4 — Filter by status=AUTHORIZED returns only authorized transactions

**Setup**: Insert 3 transactions — 2 with status=AUTHORIZED, 1 with status=SETTLED.

**Request**: `GET /internal/reporting/transactions?status=AUTHORIZED`

**Expected**:
- HTTP 200
- `content` has 2 entries, both with `status = "AUTHORIZED"`

---

### Scenario 1.5 — Combined filters work correctly

**Setup**: Insert 5 transactions across 2 merchants and 2 statuses spanning 2 months.

**Request**: `GET /internal/reporting/transactions?merchantId=merchant-001&status=SETTLED&from=2026-06-01T00:00:00Z&to=2026-06-30T23:59:59Z`

**Expected**:
- HTTP 200
- Only transactions matching ALL three conditions returned

---

### Scenario 1.6 — Date range exceeding 90 days returns 400

**Request**: `GET /internal/reporting/transactions?from=2026-01-01T00:00:00Z&to=2026-06-01T00:00:00Z`
(gap = 151 days > 90 days)

**Expected**:
- HTTP 400
- `{"error": "Date range exceeds maximum of 90 days"}`

---

### Scenario 1.7 — Empty result set returns empty list, not error

**Setup**: No transactions for `tok-nonexistent`.

**Request**: `GET /internal/reporting/transactions?cardToken=tok-nonexistent`

**Expected**:
- HTTP 200
- `{"content": [], "totalElements": 0, ...}`

---

### Scenario 1.8 — Pagination works correctly

**Setup**: Insert 25 transactions for the same merchant.

**Request**: `GET /internal/reporting/transactions?merchantId=merchant-001&page=1&size=10`

**Expected**:
- HTTP 200
- `content` has 10 entries (page 1, 0-indexed)
- `totalElements = 25`, `totalPages = 3`

---

## US2: Merchant Summary (Priority: P2)

### Scenario 2.1 — Single merchant summary returns correct aggregated totals

**Setup**: Insert 2 `merchant_settlements` records for `merchant-001` in different batches:
- Batch A: 3 txns, gross=300.00, fee=6.00, net=294.00
- Batch B: 2 txns, gross=200.00, fee=4.00, net=196.00

**Request**: `GET /internal/reporting/merchants/merchant-001/summary?from=2026-06-01&to=2026-06-30`

**Expected**:
- HTTP 200
- `transactionCount = 5`
- `grossAmount = "500.00"`
- `totalInterchangeFee = "10.00"`
- `netSettlementAmount = "490.00"`
- `batchCount = 2`

---

### Scenario 2.2 — Merchant with no settlements returns zero summary (not 404)

**Request**: `GET /internal/reporting/merchants/merchant-new/summary`

**Expected**:
- HTTP 200
- `{"merchantId": "merchant-new", "transactionCount": 0, "grossAmount": "0.00", ...}`

---

### Scenario 2.3 — All-merchants summary returns per-merchant aggregates

**Setup**: Insert settlement records for 3 merchants.

**Request**: `GET /internal/reporting/merchants/summary`

**Expected**:
- HTTP 200
- `content` has 3 entries, one per merchant
- Each entry has correct aggregated totals

---

## US3: Settlement Reports (Priority: P3)

### Scenario 3.1 — Get batch by ID returns batch summary

**Setup**: Insert a `settlement_batches` record with status=COMPLETED.

**Request**: `GET /internal/reporting/settlements/{batchId}`

**Expected**:
- HTTP 200
- `batchId`, `status`, `triggeredBy`, `totalTransactionsProcessed`, `totalGrossAmount`, `totalInterchangeFees`, `totalNetAmount`, `startedAt`, `completedAt` all present and correct

---

### Scenario 3.2 — Get per-merchant settlement within batch

**Setup**: Insert a `merchant_settlements` record for `(batchId, merchant-001)`.

**Request**: `GET /internal/reporting/settlements/{batchId}/merchants/merchant-001`

**Expected**:
- HTTP 200
- `merchantId = "merchant-001"`, `batchId`, `transactionCount`, `grossAmount`, `totalInterchangeFee`, `netSettlementAmount`, `settlementDate` all present

---

### Scenario 3.3 — Unknown batch ID returns 404

**Request**: `GET /internal/reporting/settlements/nonexistent-batch`

**Expected**:
- HTTP 404
- `{"error": "Settlement batch not found: nonexistent-batch"}`

---

### Scenario 3.4 — Known batch but unknown merchant returns 404

**Setup**: Batch exists but no merchant_settlement record for `ghost-merchant`.

**Request**: `GET /internal/reporting/settlements/{batchId}/merchants/ghost-merchant`

**Expected**:
- HTTP 404
- `{"error": "No settlement found for merchantId=ghost-merchant in batchId=..."}`

---

## Unit Test Scenarios

### ReportingService (Mockito)

- `getTransactions()` with null cardToken → calls repository with no cardToken predicate
- `getTransactions()` with dateRange > 90 days → throws `InvalidDateRangeException`
- `getMerchantSummary()` with no results → returns zero-value summary (not exception)
- `getBatch()` not found → throws `ResourceNotFoundException`
- `getMerchantSettlement()` not found → throws `ResourceNotFoundException`

### ReportingController (@WebMvcTest)

- `GET /internal/reporting/transactions` → 200 with paged response
- `GET /internal/reporting/transactions?status=INVALID` → 400 with field errors
- `GET /internal/reporting/settlements/missing` → 404 propagated from service
- `POST /internal/reporting/transactions` → 405 Method Not Allowed

### GlobalExceptionHandler (direct unit test)

- `ResourceNotFoundException` → 404 with error message
- `InvalidDateRangeException` → 400 with error message
- `MethodArgumentNotValidException` → 400 with field error list
- `MethodArgumentNotValidException` with null defaultMessage → fallback "invalid"
