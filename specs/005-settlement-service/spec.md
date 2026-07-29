# Feature Specification: Settlement Service

**Feature Branch**: `005-settlement-service`

**Created**: 2026-06-13

**Status**: Draft

**Input**: User description: "Build the settlement-service: runs scheduled end-of-day batch processing, picks up all AUTHORIZED transactions, groups them by merchant, calculates settlement amounts after deducting interchange fees, marks transactions as SETTLED in CockroachDB, publishes TransactionSettled events to Kafka, generates a settlement report per merchant. Handles partial batch failures gracefully — already settled transactions are skipped (idempotent). TDD with 100% coverage mandatory. Uses Spring Batch for batch processing."

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 — End-of-Day Batch Settlement (Priority: P1)

Every day at a scheduled time, the system automatically processes all transactions that have been authorized but not yet settled. For each merchant that has pending authorized transactions, the system calculates the net amount owed after deducting interchange fees and records the settlement. Transactions are then marked as settled so they are not processed again. If the batch is interrupted mid-run or re-triggered, already-settled transactions are silently skipped, ensuring each transaction is settled exactly once.

**Why this priority**: Core financial operation — without this, merchants are never paid and the credit card system cannot function. All other features depend on settlements being calculated correctly and idempotently.

**Independent Test**: Seed 10 AUTHORIZED transactions across 3 merchants, trigger the batch, assert all 10 transactions are now SETTLED, each merchant has a settlement record with correct gross and net amounts, and re-triggering the batch produces no additional settlement records or duplicate processing.

**Acceptance Scenarios**:

1. **Given** 10 AUTHORIZED transactions exist across 3 merchants, **When** the end-of-day batch runs, **Then** all 10 transactions are marked SETTLED, 3 merchant settlement records are created with correct gross amounts and interchange deductions, and the batch completes successfully.

2. **Given** a batch run is partially interrupted after settling 6 of 10 transactions, **When** the batch is re-triggered, **Then** only the 4 remaining AUTHORIZED transactions are settled; the 6 already-settled transactions are skipped with no duplicates.

3. **Given** no AUTHORIZED transactions exist, **When** the batch runs, **Then** the batch completes immediately with zero settlements and no errors.

4. **Given** a single merchant has 0 AUTHORIZED transactions but other merchants do, **When** the batch runs, **Then** only merchants with transactions receive settlement records.

5. **Given** the batch is scheduled to run at 23:59 UTC daily, **When** the schedule fires, **Then** the batch starts within 60 seconds of the scheduled time.

---

### User Story 2 — Settlement Event Publishing (Priority: P2)

When a transaction is settled, a `TransactionSettled` event is published to a Kafka topic so downstream services (such as ledger, reporting, and notification services) can react to the settlement in real time. Each event carries enough information to identify the transaction, the merchant, the gross amount, the interchange fee deducted, and the net settlement amount. Events are published after the database record is committed to prevent phantom events on failure.

**Why this priority**: Downstream services — including cardholder statements, merchant reporting, and the general ledger — depend on these events. Without event publishing, settlements exist only in the database and are invisible to the rest of the platform.

**Independent Test**: Trigger a batch with 5 AUTHORIZED transactions, assert exactly 5 `TransactionSettled` Kafka events are published, each event contains the correct transactionId, merchantId, grossAmount, interchangeFee, and netAmount with no duplicate events on batch re-run.

**Acceptance Scenarios**:

1. **Given** a transaction is successfully settled, **When** the settlement batch commits the status change, **Then** a `TransactionSettled` Kafka event is published containing transactionId, merchantId, grossAmount, interchangeFee, netAmount, settlementDate, and batchId.

2. **Given** a transaction settlement fails (e.g., database error), **When** the batch handles the failure, **Then** no `TransactionSettled` event is published for that transaction, and the transaction remains AUTHORIZED for retry.

3. **Given** a batch re-run on already-settled transactions, **When** the batch skips them, **Then** no duplicate `TransactionSettled` events are published.

---

### User Story 3 — Merchant Settlement Report (Priority: P3)

After each batch run, a settlement summary report is generated per merchant. The report shows the merchant identifier, total number of transactions settled, gross transaction amount, total interchange fees deducted, and net settlement amount. Reports are queryable via a REST endpoint so that downstream reconciliation tools and merchant portals can retrieve them. Reports are immutable once generated and associated with a specific batch run.

**Why this priority**: Reports are necessary for merchant reconciliation and auditing but are not required for the batch processing itself to succeed. Settlement and event publishing deliver immediate business value; reports are a secondary artifact.

**Independent Test**: Run a batch with 8 AUTHORIZED transactions for 2 merchants (5 and 3 transactions respectively), then query the REST endpoint for each merchant's report and assert the transaction counts, gross amounts, fee deductions, and net amounts are correct for that batch.

**Acceptance Scenarios**:

1. **Given** a batch has completed settling transactions for merchant M1, **When** the report endpoint is queried for M1 and the batch ID, **Then** the response includes: transactionCount, grossAmount, totalInterchangeFee, netSettlementAmount, settlementDate, and batchId.

2. **Given** a batch has run for multiple merchants, **When** the report endpoint is queried for a specific merchant, **Then** only that merchant's settlement data is returned (not aggregated across all merchants).

3. **Given** a batch ID and merchant ID for which no settlement occurred, **When** the report endpoint is queried, **Then** the response is 404 with a clear error message.

4. **Given** a report has been generated, **When** the same batch is re-run (idempotent), **Then** the report values do not change (immutable once generated).

---

### Edge Cases

- What happens when a transaction has a zero amount? (Zero-amount settlements should be skipped as no monetary movement occurs.)
- What happens if the interchange fee rate configuration is missing or invalid? (Batch must fail fast with a clear error rather than silently applying zero fees.)
- What happens when the Kafka broker is unavailable during event publishing? (Settlement DB commit succeeds; event publishing failure is logged and retried without rolling back the settlement.)
- What if a transaction transitions out of AUTHORIZED status (e.g., cancelled) between batch read and settlement write? (Optimistic concurrency check — skip the transaction and log a warning; do not mark it SETTLED.)
- What if the batch runs more than once on the same day? (Idempotency ensures already-settled transactions are skipped; a new batch ID is generated per run so reports remain distinct.)
- What if a merchant ID is null or missing on a transaction? (Those transactions are skipped with an error log; they do not block other merchants' settlements.)

---

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST process all transactions in AUTHORIZED status during each batch run, grouped by merchant identifier.
- **FR-002**: System MUST calculate the net settlement amount for each transaction by deducting a configurable interchange fee from the gross transaction amount.
- **FR-003**: System MUST mark each successfully settled transaction as SETTLED with a settlement timestamp and batch identifier.
- **FR-004**: System MUST skip transactions already in SETTLED status during any batch run without modifying their records (idempotent processing).
- **FR-005**: System MUST publish one `TransactionSettled` event per settled transaction to a Kafka topic after the settlement database record is committed.
- **FR-006**: System MUST NOT publish a `TransactionSettled` event if the corresponding settlement database write fails.
- **FR-007**: System MUST generate a settlement report per merchant per batch run, containing: transaction count, gross amount, total interchange fees, and net settlement amount.
- **FR-008**: System MUST trigger the settlement batch automatically on a configurable daily schedule (default: 23:59 UTC).
- **FR-009**: System MUST allow manual triggering of the settlement batch via an internal API endpoint, for operational use.
- **FR-010**: System MUST handle partial batch failures gracefully: a failure settling one merchant's transactions must not prevent other merchants from being processed.
- **FR-011**: System MUST skip transactions with null or missing merchant identifiers and log a warning without failing the batch.
- **FR-012**: System MUST skip transactions with a zero gross amount without creating a settlement record.
- **FR-013**: System MUST associate each batch run with a unique batch identifier so that reports and settled records are traceable to the run that produced them.
- **FR-014**: Settlement reports MUST be immutable once generated — re-running the batch does not overwrite or modify existing reports.
- **FR-015**: System MUST expose a REST endpoint to query settlement reports by merchant and batch identifier.

### Key Entities

- **Transaction**: A financial transaction in AUTHORIZED status awaiting settlement; has an identifier, merchant identifier, gross amount, and status. Owned by the transaction-service database.
- **SettlementBatch**: Represents one execution of the settlement job; has a unique batch identifier, scheduled date, start time, end time, total transactions processed, total amount settled, and status (RUNNING, COMPLETED, FAILED).
- **MerchantSettlement**: Per-merchant aggregate result within a batch; contains merchant identifier, batch identifier, transaction count, gross amount, total interchange fee deducted, net settlement amount, and settlement date.
- **InterchangeFeeConfig**: Configuration holding the interchange fee rate (percentage) applied during settlement calculation. Configurable per environment without redeployment.
- **TransactionSettledEvent**: Kafka event emitted per settled transaction; carries transactionId, merchantId, batchId, grossAmount, interchangeFee, netAmount, settlementDate.

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: All AUTHORIZED transactions are processed within 10 minutes for a batch of up to 100,000 transactions.
- **SC-002**: Each transaction is settled exactly once across repeated batch runs — zero duplicate settlement records observed under load testing with 5 concurrent re-runs.
- **SC-003**: A `TransactionSettled` Kafka event is published for 100% of successfully settled transactions with no events published for failed or skipped transactions.
- **SC-004**: Merchant settlement reports are available via the REST endpoint within 60 seconds of batch completion.
- **SC-005**: A batch with a failure in one merchant's processing completes successfully for all other merchants — partial failure does not cause a total batch failure.
- **SC-006**: The system achieves 100% unit and integration test coverage with all tests passing on every build.
- **SC-007**: Interchange fee calculations are accurate to 2 decimal places for all transaction amounts.

---

## Assumptions

- **Interchange fee rate**: A single configurable flat percentage rate applies to all transactions (e.g., 2.0%). Tiered or card-brand-specific rates are out of scope for this version.
- **Transaction ownership**: Transaction records (with AUTHORIZED status) reside in the same CockroachDB cluster accessible to this service. The settlement-service reads and updates transaction status directly in that database.
- **Currency**: All amounts are in a single currency (USD). Multi-currency settlement is out of scope.
- **Batch window**: The daily batch runs once per calendar day. Running multiple times per day is supported via manual trigger and idempotency, but scheduled runs are once daily.
- **Settlement report delivery**: Reports are stored in the database and accessible via REST API. Email or file-based delivery is out of scope.
- **Event ordering**: `TransactionSettled` events are published on a best-effort basis after DB commit. Exactly-once Kafka semantics are not required; downstream consumers handle idempotency.
- **Merchant data**: Merchant identifiers are assumed to exist on the transaction record. No merchant lookup or enrichment is performed during settlement.
- **Manual trigger security**: The manual batch trigger endpoint is internal (not customer-facing) and protected by network policy, not application-layer auth, in this version.
- **Batch re-run on same day**: Allowed. Already-settled transactions are skipped; a new SettlementBatch record with a new batch ID is created per trigger.
