# Research: Settlement Service

**Feature**: `005-settlement-service`
**Date**: 2026-06-13

---

## Decision 1: Spring Batch Version and Spring Boot 3.x Configuration

**Decision**: Spring Batch 5.x (bundled with Spring Boot 3.2.5 via `spring-boot-starter-batch`). Do NOT use `@EnableBatchProcessing`.

**Rationale**: In Spring Boot 3.x, applying `@EnableBatchProcessing` disables `BatchAutoConfiguration`, requiring manual wiring of `JobRepository`, `JobLauncher`, and `PlatformTransactionManager`. Without it, Boot auto-configures all three beans using the primary `DataSource` and `PlatformTransactionManager` — drastically reducing boilerplate. Spring Batch 5 (released with Spring Boot 3) introduced breaking API changes from SB 4: `JobBuilderFactory` / `StepBuilderFactory` are removed; use `JobBuilder` and `StepBuilder` directly.

**Alternatives considered**:
- Quartz Scheduler: rejected — Spring Batch natively handles chunk processing, restartability, skip/retry, and job history; Quartz adds complexity without equivalent batch primitives.
- Custom batch loop: rejected — reinvents idempotency, chunk management, and failure recovery that Spring Batch provides out of the box.

---

## Decision 2: Schema Management — Flyway Owns All DDL Including Spring Batch Tables

**Decision**: `spring.batch.jdbc.initialize-schema=never`. Flyway migration `V1__create_spring_batch_schema.sql` contains the CockroachDB-compatible Spring Batch metadata DDL.

**Rationale**: The project constitution mandates Flyway for all schema management. Letting Spring Batch auto-create its tables (`always` or `embedded`) bypasses Flyway, leaving un-tracked DDL. CockroachDB is PostgreSQL-wire-compatible; the standard Spring Batch PostgreSQL schema (`org/springframework/batch/core/schema-postgresql.sql`) runs without modification on CockroachDB 23+, which supports `CREATE SEQUENCE` and `NEXTVAL`.

**Spring Batch metadata tables required**:
`BATCH_JOB_INSTANCE`, `BATCH_JOB_EXECUTION`, `BATCH_JOB_EXECUTION_PARAMS`, `BATCH_JOB_EXECUTION_CONTEXT`, `BATCH_STEP_EXECUTION`, `BATCH_STEP_EXECUTION_CONTEXT`, `BATCH_JOB_SEQ`, `BATCH_JOB_EXECUTION_SEQ`, `BATCH_STEP_EXECUTION_SEQ`

**Alternatives considered**:
- `spring.batch.jdbc.initialize-schema=always`: rejected — Spring Batch's auto DDL runs before Flyway baseline, creating un-tracked objects.
- `spring.batch.jdbc.initialize-schema=embedded`: rejected — only fires for H2/HSQLDB, not CockroachDB.

---

## Decision 3: Transaction Data — Kafka-First, No Cross-Schema DB Access

**Decision**: The settlement-service maintains its own `settlement_transactions` table populated by consuming `TransactionAuthorizedEvent` from the `transaction.authorized` Kafka topic. It never queries the transaction-service's database.

**Rationale**: Constitution Principle V explicitly prohibits cross-schema queries ("Each service owns its own CockroachDB schema; no service may query another service's tables directly"). The Kafka-first model is the canonical pattern for inter-service data sharing in this architecture. The settlement-service accumulates all AUTHORIZED transactions it needs to settle via event consumption.

**Prerequisite contract**: The `TransactionAuthorizedEvent` published by the authorization-service on `transaction.authorized` MUST include: `transactionId`, `merchantId`, `cardToken`, `maskedCardNumber`, `amount`, `currencyCode`, `authorizedAt`. The `merchantId` field does not currently exist in the authorization-service event schema and MUST be added as part of this feature. See `contracts/events.md`.

**Alternatives considered**:
- Direct DB query to transaction-service schema: rejected — explicit constitution violation (Principle V).
- Shared database schema: rejected — same violation; also creates tight coupling.
- gRPC call to transaction-service: rejected — appropriate for blocking synchronous reads, not bulk batch reads of potentially 100,000+ records.

---

## Decision 4: Idempotency — Two-Layer Approach

**Decision**: Idempotency is enforced at both the Kafka consumer layer and the Spring Batch processor layer.

**Layer 1 — Consumer idempotency** (`TransactionAuthorizedListener`): Uses the same `processed_events` table + `INSERT ... ON CONFLICT DO NOTHING` pattern as all other services in this project. Idempotency key: `settlement:TransactionAuthorized:{eventId}`. This prevents duplicate records in `settlement_transactions` from Kafka redelivery.

**Layer 2 — Batch processor idempotency** (`SettlementItemProcessor`): Checks `SettlementTransaction.status`. If already `SETTLED`, throws `AlreadySettledException`. Spring Batch's `faultTolerant().skipPolicy(AlreadySettledSkipPolicy)` absorbs the skip and continues processing the chunk. This handles the case where a batch run is interrupted and restarted — already-settled items are silently skipped.

**Rationale**: Two independent layers are necessary. Consumer idempotency alone doesn't protect against partial batch failures (a transaction can be in `settlement_transactions` once but the batch may have settled half of them before crashing). Processor idempotency alone doesn't protect against duplicate event consumption inserting duplicate rows.

---

## Decision 5: Merchant Settlement Aggregation Strategy

**Decision**: In-memory aggregation within `SettlementItemWriter.write()` using a `Map<String, MerchantSettlementAccumulator>`, flushed to `merchant_settlements` via `UPSERT` (INSERT ... ON CONFLICT (batch_id, merchant_id) DO UPDATE SET ...) at the end of each chunk.

**Rationale**: Spring Batch processes records sequentially within a chunk. Accumulating merchant totals in-memory per chunk and UPSERTing avoids N+1 DB round-trips. The UPSERT pattern handles idempotent re-runs correctly — if a chunk is replayed (Spring Batch restarts failed step from last checkpoint), the UPSERT overwrites the partial values from the previous attempt.

**Alternatives considered**:
- Separate aggregation step after settlement step: rejected — adds complexity; requires inter-step data sharing (JobExecutionContext or DB staging table).
- GROUP BY SQL query after settlement: rejected — a post-processing query can't easily handle partial re-runs; the UPSERT approach is inherently restartable.

---

## Decision 6: Interchange Fee Calculation

**Decision**: Configurable flat percentage rate loaded from `application.yml` via `@ConfigurationProperties(prefix = "settlement")`. Default: 2.0% (`settlement.interchange-fee-rate: 0.02`). Calculation: `interchangeFee = amount.multiply(rate).setScale(2, RoundingMode.HALF_UP)`. `netAmount = amount.subtract(interchangeFee)`.

**Rationale**: The spec explicitly defines a flat configurable rate as the v1 scope (Assumptions section). `BigDecimal` with `HALF_UP` rounding at 2 decimal places matches standard financial precision requirements for USD settlement amounts.

**Alternatives considered**:
- Tiered rates per card brand: deferred to v2 per spec Assumptions.
- Per-merchant negotiated rates: deferred; would require a merchant rate configuration store.
- `double` arithmetic: rejected — floating-point imprecision is unacceptable for financial calculations.

---

## Decision 7: Job Triggering — Scheduler + REST Endpoint

**Decision**: `SettlementScheduler` uses Spring's `@Scheduled` with a cron expression (`0 59 23 * * *` = 23:59 UTC daily, configurable via `settlement.batch.cron`). A REST `POST /internal/settlement/trigger` endpoint provides manual triggering via `SettlementService.launchJob()`.

**Rationale**: The spec requires both scheduled (FR-008) and manual trigger (FR-009) capabilities. Spring's `@Scheduled` is the idiomatic choice for fixed-schedule operations in Spring Boot. The REST endpoint uses `JobLauncher.run(settlementJob, jobParameters)` with a unique parameter (`triggeredAt=Instant.now().toString()`) to allow multiple runs per day (Spring Batch considers each unique `JobParameters` combination a new `JobInstance`).

**Alternatives considered**:
- Quartz Scheduler: rejected — adds external dependency and complexity for a single cron job.
- Kubernetes CronJob calling the REST endpoint: valid production pattern but redundant given @Scheduled; Spring's scheduler is simpler for a single service.

---

## Decision 8: TransactionSettled Event Publishing Timing

**Decision**: `TransactionSettledEvent` is published inside `SettlementItemWriter.write()` after the JDBC batch update for the chunk commits within the Spring Batch chunk transaction. If Kafka publish fails, the exception is caught, logged as WARN, and the settlement DB record is NOT rolled back.

**Rationale**: The spec Assumptions state: "Exactly-once Kafka semantics are not required; downstream consumers handle idempotency." Rolling back the settlement on Kafka failure would be worse — the transaction would remain in AUTHORIZED status and be re-settled on the next batch run, potentially with a different `batchId`. Tolerating Kafka publish failure (with monitoring/alerting) and relying on downstream idempotency is the correct trade-off for a settlement system.

**Alternatives considered**:
- Transactional outbox pattern: valid but over-engineered for this spec; deferred to v2 if Kafka reliability requirements increase.
- Kafka producer with `acks=all` + retries: enabled (3 retries, exponential backoff) to minimize publish failures, but not a full guarantee.
