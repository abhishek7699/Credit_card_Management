# Feature Specification: Reporting Service API

**Feature Branch**: `006-reporting-service-api`

**Created**: 2026-06-15

**Status**: Draft

**Input**: User description: "Build the reporting-service: provides read-only REST API for transaction history, merchant summaries, and settlement reports. Queries CockroachDB directly. No Kafka producing. No state changes. Supports filtering by card token, merchant ID, date range, and status. TDD with 100% coverage mandatory."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Transaction History Lookup (Priority: P1)

A business analyst or operations team member needs to view the history of transactions for a specific card or across all cards, optionally filtered by date range and status. This is the most critical query because transaction history is the fundamental audit trail for any payment operation.

**Why this priority**: Transaction history is queried most frequently and underpins fraud investigations, customer support, and dispute resolution. Without it, no other reporting makes sense.

**Independent Test**: Can be tested independently by querying `/reporting/transactions` with various filter combinations (card token, date range, status) and verifying that the response contains the correct subset of transactions with all required fields.

**Acceptance Scenarios**:

1. **Given** a set of transactions in the system, **When** a consumer requests transaction history filtered by card token, **Then** only transactions for that card token are returned with full transaction details.
2. **Given** transactions spanning multiple dates, **When** a consumer filters by date range (from/to), **Then** only transactions within the specified range are returned.
3. **Given** transactions in various statuses (AUTHORIZED, SETTLED, DECLINED), **When** a consumer filters by status, **Then** only transactions matching that status are returned.
4. **Given** no filters are applied, **When** a consumer queries transaction history, **Then** all transactions are returned in reverse chronological order with pagination applied.
5. **Given** multiple filters are combined (card token + date range + status), **When** a consumer queries, **Then** only transactions matching all conditions are returned.
6. **Given** a card token with no transactions, **When** a consumer queries by that card token, **Then** an empty list is returned (not an error).

---

### User Story 2 - Merchant Summary Report (Priority: P2)

A merchant operations or finance team member needs to see aggregated transaction summaries by merchant — total transaction count, total gross amount, total fees, and net amount — optionally filtered by date range or settlement batch.

**Why this priority**: Merchant summaries drive reconciliation and financial reporting. They answer "how much did merchant X process this month?" without requiring consumers to aggregate raw transactions themselves.

**Independent Test**: Can be tested independently by inserting known settled transactions for multiple merchants and verifying that the summary endpoint returns correct aggregated counts and amounts per merchant.

**Acceptance Scenarios**:

1. **Given** settled transactions for multiple merchants, **When** a consumer requests a summary for a specific merchant ID, **Then** the response contains total transaction count, gross amount, total fees, and net settlement amount for that merchant.
2. **Given** a merchant with transactions in multiple date ranges, **When** a consumer filters by date range, **Then** only transactions within that range are included in the aggregated totals.
3. **Given** a merchant with no transactions, **When** a consumer requests a summary for that merchant, **Then** the response returns zero counts and amounts (not an error).
4. **Given** a request for all merchants' summaries, **When** no merchant ID filter is applied, **Then** a list of per-merchant summaries is returned.

---

### User Story 3 - Settlement Report Lookup (Priority: P3)

A finance or settlements operations team member needs to retrieve settlement reports — either the summary of a specific batch run or the per-merchant settlement details within a batch — to reconcile end-of-day payouts.

**Why this priority**: Settlement reports are needed after each batch run to confirm payouts. They are less frequently queried than transaction history but critical for financial reconciliation.

**Independent Test**: Can be tested independently by querying `/reporting/settlements/{batchId}` and `/reporting/settlements/{batchId}/merchants/{merchantId}` after a batch has been recorded, and verifying the returned data matches the settlement records.

**Acceptance Scenarios**:

1. **Given** a completed settlement batch, **When** a consumer requests the batch summary by batch ID, **Then** the response includes batch status, total transactions processed, gross amount, fees, and net amount.
2. **Given** a completed batch with multiple merchant settlements, **When** a consumer requests the per-merchant detail for that batch and a specific merchant, **Then** the correct per-merchant settlement record is returned.
3. **Given** an unknown batch ID, **When** a consumer queries for it, **Then** a 404 response is returned with a descriptive error message.
4. **Given** a known batch with no settlement for a specific merchant, **When** a consumer queries per-merchant detail, **Then** a 404 response is returned.

---

### Edge Cases

- What happens when the date range is invalid (from > to)? → Return 400 with a descriptive error.
- What happens when pagination parameters are out of range (page=0, size=0, or size > max)? → Return 400 or clamp to valid defaults.
- What happens when a filter value is malformed (e.g., non-UUID card token format)? → Return an empty result set; filtering is best-effort on string values.
- How does the system behave under high concurrency (many simultaneous read requests)? → Reads are non-blocking; the database is the scaling bottleneck, handled at the infrastructure layer.
- What if date range spans more than 90 days? → Enforce a maximum date range of 90 days to prevent full-table scans; return 400 if exceeded.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST expose a read-only REST endpoint to list transactions, supporting filters by card token, merchant ID, date range (from/to), and status.
- **FR-002**: System MUST expose a read-only REST endpoint to retrieve merchant summary data, supporting filters by merchant ID and date range.
- **FR-003**: System MUST expose a read-only REST endpoint to retrieve a settlement batch summary by batch ID.
- **FR-004**: System MUST expose a read-only REST endpoint to retrieve per-merchant settlement detail by batch ID and merchant ID.
- **FR-005**: All list endpoints MUST support pagination with configurable page size (default: 20, max: 100).
- **FR-006**: All list endpoints MUST return results in reverse chronological order by default.
- **FR-007**: System MUST return 404 with a descriptive error message when a requested resource (batch, merchant settlement) does not exist.
- **FR-008**: System MUST return 400 with descriptive validation errors for invalid filter parameters (invalid date range, unsupported status values, out-of-range pagination).
- **FR-009**: System MUST NOT allow any write operations — all endpoints are strictly read-only; any method other than GET on reporting endpoints returns 405.
- **FR-010**: System MUST enforce a maximum date range of 90 days on filtered queries to prevent excessive load.
- **FR-011**: System MUST return card tokens and masked card numbers in responses; raw card numbers must never appear in any response or log.
- **FR-012**: System MUST support combined filter parameters (e.g., card token + date range + status applied simultaneously).
- **FR-013**: All endpoints MUST respond with consistent JSON structure; empty result sets return an empty list, not null or an error.

### Key Entities

- **Transaction**: A record of a single payment event (authorized, settled, or declined). Key attributes: transaction ID, card token, masked card number, merchant ID, amount, currency, status, authorized timestamp, settled timestamp.
- **MerchantSummary**: An aggregated view of transactions for a single merchant over a period. Key attributes: merchant ID, transaction count, gross amount, total fees, net settlement amount, currency, period.
- **SettlementBatch**: The record of a settlement run. Key attributes: batch ID, status, triggered by, total transactions processed, gross amount, fees, net amount, started at, completed at.
- **MerchantSettlement**: The per-merchant payout record within a settlement batch. Key attributes: merchant ID, batch ID, transaction count, gross amount, total interchange fee, net settlement amount, settlement date.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Consumers can retrieve any transaction history within a 30-day window in under 1 second under normal load.
- **SC-002**: All filtering combinations (single filter, multi-filter, no filter) produce correct results with 100% accuracy.
- **SC-003**: The service handles 500 concurrent read requests without degradation (response times remain under 2 seconds).
- **SC-004**: 100% of invalid requests (bad date ranges, missing required params) return appropriate 4xx responses with descriptive messages — no 500 errors for client mistakes.
- **SC-005**: No raw card numbers or sensitive payment data appear in any response, log entry, or error message.
- **SC-006**: All endpoints are covered by automated tests, with 100% instruction and branch coverage verified on every build.
- **SC-007**: Empty result sets (valid filters with no matching data) return in under 200ms — no slow "no results" queries.

## Assumptions

- The reporting-service reads from the same database schema as the transaction-service and settlement-service; no data duplication or ETL is required.
- Callers of the API are internal services or internal tooling (ops dashboards, finance tools); no end-user authentication is required in v1 — network-policy-level access control is sufficient.
- Card tokens used in filters are opaque string identifiers, not raw PANs; the service does not need to validate token format beyond treating them as strings.
- All monetary amounts are stored and returned as decimal values in the base currency (USD assumed unless the transaction record specifies otherwise).
- Pagination is cursor-based by default using page number and page size; offset pagination is acceptable for v1 given expected query volumes.
- The maximum date range of 90 days is a reasonable default to prevent performance issues; this can be made configurable in a future version.
- The service does not aggregate across databases or external sources — all data is available in the single CockroachDB instance.
- Write operations (POST, PUT, DELETE, PATCH) are rejected at the controller level; no database-level write prevention is in scope for v1.
