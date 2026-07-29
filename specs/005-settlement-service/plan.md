# Implementation Plan: Settlement Service

**Branch**: `005-settlement-service` | **Date**: 2026-06-13 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `/specs/005-settlement-service/spec.md`

---

## Summary

End-of-day settlement service using Spring Batch 5.x (Spring Boot 3.2.5) that consumes `TransactionAuthorizedEvent` from Kafka to maintain a local authorized-transaction store, then runs a scheduled chunk-oriented batch job to: calculate interchange fees (configurable flat rate, default 2.0%), mark transactions SETTLED in its own CockroachDB schema, UPSERT per-merchant settlement aggregates, and publish `TransactionSettledEvent` to the `transaction.settled` topic. Three REST endpoints expose manual trigger and settlement report query. Full idempotency at both the Kafka consumer layer (processed_events table) and the Spring Batch processor layer (AlreadySettledException skip policy). TDD with 100% JaCoCo instruction + branch coverage mandatory.

---

## Technical Context

**Language/Version**: Java 17 (LTS)

**Primary Dependencies**:
- `spring-boot-starter-batch` (Spring Batch 5.x)
- `spring-boot-starter-web` (REST: trigger + report endpoints)
- `spring-boot-starter-data-jpa`
- `spring-boot-starter-actuator`
- `spring-boot-starter-validation`
- `spring-kafka`
- `flyway-core`
- `postgresql` (CockroachDB-compatible JDBC driver)
- `jackson-datatype-jsr310`
- `net.logstash.logback:logstash-logback-encoder:7.4`
- Test: `spring-boot-starter-test`, `spring-batch-test`, `spring-kafka-test`, `testcontainers:postgresql`, `awaitility`

**Storage**: CockroachDB Serverless. Settlement-service owns five tables: `settlement_transactions`, `settlement_batches`, `merchant_settlements`, `processed_events`, plus Spring Batch metadata (V1). No cross-schema queries to any other service.

**Testing**: JUnit 5 + Mockito + `@SpringBootTest` + `@SpringBatchTest` + Testcontainers (PostgreSQL) + `@EmbeddedKafka` + JaCoCo 0.8.11 (100% instruction + branch gate, excluding `SettlementServiceApplication`)

**Target Platform**: Linux server / Kubernetes on GKE

**Project Type**: Microservice (Spring Boot batch + REST)

**Performance Goals**: 100,000 transactions processed in under 10 minutes (chunk size = 100, configurable)

**Constraints**: 100% JaCoCo instruction + branch coverage on all production classes (excluding main application class)

**Scale/Scope**: 6th of 7 backend microservices (Phase 1 backend completion)

---

## Constitution Check

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Zero Raw Card Data | ✅ PASS | `settlement_transactions` stores `card_token` (vault token) + `masked_card_number` only; `PanMaskingFilter` TurboFilter on all loggers |
| II. 100% TDD Coverage | ✅ PASS | JaCoCo 0.8.11 gate — 100% instruction + branch; all classes unit + integration tested |
| III. Idempotent Consumers | ✅ PASS | `processed_events` table + `INSERT ON CONFLICT DO NOTHING`; batch processor `AlreadySettledException` skip policy |
| IV. One-Step Development | ✅ PASS | Tasks: test first, then implementation; checkpoint per US phase |
| V. Layered Architecture | ✅ PASS | Listener → Service → Repository; no cross-schema DB access; Kafka-first data model |
| VI. Observability | ✅ PASS | Structured JSON logging; traceId propagated; settlement state transitions in audit log |
| VII. Proto-First | N/A | No gRPC in this service; Kafka-only inter-service communication |
| VIII. Frontend Sharing | N/A | Backend microservice only |

**No gate violations.**

---

## Project Structure

### Documentation (this feature)

```text
specs/005-settlement-service/
├── plan.md              # This file
├── research.md          # Phase 0 — Spring Batch patterns, CockroachDB, design decisions
├── data-model.md        # Phase 1 — entities, schema, state machines
├── quickstart.md        # Phase 1 — test scenarios (14 scenarios across 3 user stories)
├── contracts/
│   ├── api.md           # Phase 1 — REST API contract (trigger, reports, batches)
│   └── events.md        # Phase 1 — Kafka event schemas (inbound + outbound)
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by /speckit-plan)
```

### Source Code (repository root)

```text
services/settlement-service/
├── build.gradle
├── Dockerfile
└── src/
    ├── main/
    │   ├── java/com/creditcard/settlement/
    │   │   ├── SettlementServiceApplication.java
    │   │   ├── batch/
    │   │   │   ├── SettlementJobConfig.java
    │   │   │   ├── reader/
    │   │   │   │   └── AuthorizedTransactionReader.java
    │   │   │   ├── processor/
    │   │   │   │   └── SettlementItemProcessor.java
    │   │   │   └── writer/
    │   │   │       └── SettlementItemWriter.java
    │   │   ├── config/
    │   │   │   ├── BatchConfig.java
    │   │   │   ├── KafkaConsumerConfig.java
    │   │   │   ├── KafkaProducerConfig.java
    │   │   │   └── logging/
    │   │   │       └── PanMaskingFilter.java
    │   │   ├── domain/
    │   │   │   ├── SettlementTransaction.java
    │   │   │   ├── SettlementBatch.java
    │   │   │   └── MerchantSettlement.java
    │   │   ├── exception/
    │   │   │   ├── AlreadySettledException.java
    │   │   │   └── MerchantSettlementNotFoundException.java
    │   │   ├── kafka/
    │   │   │   ├── event/
    │   │   │   │   ├── TransactionAuthorizedEvent.java
    │   │   │   │   └── TransactionSettledEvent.java
    │   │   │   └── TransactionAuthorizedListener.java
    │   │   ├── model/
    │   │   │   └── SettledItem.java
    │   │   ├── repository/
    │   │   │   ├── SettlementTransactionRepository.java
    │   │   │   ├── SettlementBatchRepository.java
    │   │   │   ├── MerchantSettlementRepository.java
    │   │   │   └── ProcessedEventRepository.java
    │   │   ├── scheduler/
    │   │   │   └── SettlementScheduler.java
    │   │   ├── service/
    │   │   │   ├── SettlementService.java
    │   │   │   └── InterchangeFeeService.java
    │   │   └── web/
    │   │       ├── SettlementController.java
    │   │       ├── GlobalExceptionHandler.java
    │   │       └── dto/
    │   │           ├── BatchTriggerResponse.java
    │   │           └── MerchantSettlementResponse.java
    │   └── resources/
    │       ├── application.yml
    │       ├── logback-spring.xml
    │       └── db/migration/
    │           ├── V1__create_spring_batch_schema.sql
    │           ├── V2__create_settlement_transactions.sql
    │           ├── V3__create_settlement_batches.sql
    │           ├── V4__create_merchant_settlements.sql
    │           └── V5__create_processed_events.sql
    └── test/
        ├── java/com/creditcard/settlement/
        │   ├── batch/
        │   │   ├── processor/
        │   │   │   └── SettlementItemProcessorTest.java
        │   │   └── writer/
        │   │       └── SettlementItemWriterTest.java
        │   ├── config/
        │   │   └── logging/
        │   │       └── PanMaskingFilterTest.java
        │   ├── domain/
        │   │   ├── SettlementTransactionTest.java
        │   │   ├── SettlementBatchTest.java
        │   │   └── MerchantSettlementTest.java
        │   ├── exception/
        │   │   └── ExceptionTest.java
        │   ├── integration/
        │   │   ├── TestKafkaConfig.java
        │   │   ├── SettlementBatchIntegrationTest.java
        │   │   ├── KafkaEventPublishingIntegrationTest.java
        │   │   └── MerchantReportIntegrationTest.java
        │   ├── kafka/
        │   │   └── TransactionAuthorizedListenerTest.java
        │   ├── model/
        │   │   └── SettledItemTest.java
        │   ├── scheduler/
        │   │   └── SettlementSchedulerTest.java
        │   ├── service/
        │   │   ├── SettlementServiceTest.java
        │   │   └── InterchangeFeeServiceTest.java
        │   └── web/
        │       ├── SettlementControllerTest.java
        │       └── GlobalExceptionHandlerTest.java
        └── resources/
            └── application-test.yml
```

**Structure Decision**: Option 3 microservice layout (from prior services). Follows the same pattern as `notification-service` and `fraud-detection-service`. Spring Batch classes live under `batch/` alongside `domain/`, `service/`, `web/`, and `kafka/` packages.

---

## Key Design Decisions

### 1. Spring Batch 5.x — No `@EnableBatchProcessing`

In Spring Boot 3.x, `@EnableBatchProcessing` **disables** `BatchAutoConfiguration`. Do not annotate any `@Configuration` class with it. Instead:
- `JobRepository` auto-configured via `BatchAutoConfiguration` using the primary `DataSource`
- `PlatformTransactionManager` (JPA `JpaTransactionManager`) auto-configured
- `JobLauncher` auto-configured as `TaskExecutorJobLauncher`

`SettlementJobConfig.java` uses `JobBuilder` + `StepBuilder` (Spring Batch 5 API, no factories):

```java
@Bean
public Job settlementJob(Step settlementStep, JobRepository jobRepository) {
    return new JobBuilder("settlementJob", jobRepository)
            .start(settlementStep)
            .build();
}

@Bean
public Step settlementStep(JobRepository jobRepository,
                            PlatformTransactionManager txManager,
                            JdbcCursorItemReader<SettlementTransaction> reader,
                            SettlementItemProcessor processor,
                            SettlementItemWriter writer) {
    return new StepBuilder("settlementStep", jobRepository)
            .<SettlementTransaction, SettledItem>chunk(chunkSize, txManager)
            .reader(reader)
            .processor(processor)
            .writer(writer)
            .faultTolerant()
            .skipPolicy(new AlreadySettledSkipPolicy())
            .build();
}
```

### 2. Flyway Schema Management

`spring.batch.jdbc.initialize-schema=never` in `application.yml`. Flyway `V1__create_spring_batch_schema.sql` contains the verbatim Spring Batch PostgreSQL schema (compatible with CockroachDB 23+). CockroachDB supports `CREATE SEQUENCE` and `NEXTVAL`.

`spring.batch.job.enabled=false` prevents any job from auto-running on startup.

### 3. Kafka-First Data Model

The settlement-service does NOT query the transaction-service database (Constitution Principle V). It maintains `settlement_transactions` populated by consuming `TransactionAuthorizedEvent` from `transaction.authorized`.

**Two-factory Kafka consumer** (same pattern as notification-service): Only one event type consumed, so a single `ConcurrentKafkaListenerContainerFactory<String, TransactionAuthorizedEvent>` bean is sufficient. `AckMode.MANUAL_IMMEDIATE` + `finally { ack.acknowledge() }`. `AUTO_OFFSET_RESET_CONFIG = "earliest"` set programmatically (critical for `@EmbeddedKafka` reliability).

### 4. Idempotency Architecture

**Layer 1 — Consumer**: `processed_events` table. Key format: `settlement:TransactionAuthorized:{eventId}`. Native SQL: `INSERT INTO processed_events (event_key, processed_at) VALUES (:key, NOW()) ON CONFLICT DO NOTHING` returning `int`. If 0, discard.

**Layer 2 — Batch Processor**: `SettlementItemProcessor` checks `transaction.getStatus()`. If `SETTLED`, throws `AlreadySettledException` (unchecked). `AlreadySettledSkipPolicy.shouldSkip()` returns `true` for `AlreadySettledException`, `false` for all others. Limit: `Integer.MAX_VALUE`.

```java
public class AlreadySettledSkipPolicy implements SkipPolicy {
    @Override
    public boolean shouldSkip(Throwable t, long skipCount) {
        return t instanceof AlreadySettledException;
    }
}
```

### 5. Merchant Settlement Aggregation (UPSERT Pattern)

`SettlementItemWriter.write()` accumulates `SettledItem` list into a `Map<String, MerchantSettlementAccumulator>` in memory, then UPSERTs to `merchant_settlements`:

```sql
INSERT INTO merchant_settlements (id, batch_id, merchant_id, transaction_count,
    gross_amount, total_interchange_fee, net_settlement_amount, currency_code,
    settlement_date, created_at)
VALUES (gen_random_uuid(), :batchId, :merchantId, :count, :gross, :fee, :net, 'USD', :date, NOW())
ON CONFLICT (batch_id, merchant_id)
DO UPDATE SET transaction_count = EXCLUDED.transaction_count,
             gross_amount = EXCLUDED.gross_amount,
             total_interchange_fee = EXCLUDED.total_interchange_fee,
             net_settlement_amount = EXCLUDED.net_settlement_amount
```

This makes chunk replay idempotent — if a chunk is re-processed by Spring Batch after a step failure, the UPSERT overwrites the partial values from the failed attempt.

### 6. Interchange Fee Calculation

`InterchangeFeeService` reads rate from `@ConfigurationProperties`:

```yaml
settlement:
  interchange-fee-rate: 0.02
  batch:
    chunk-size: 100
    cron: "0 59 23 * * *"
```

```java
public BigDecimal calculateFee(BigDecimal amount) {
    return amount.multiply(interchangeFeeRate)
                 .setScale(2, RoundingMode.HALF_UP);
}
```

Validated at startup: `@DecimalMin("0.00") @DecimalMax("0.50") BigDecimal interchangeFeeRate`.

### 7. Job Launching and Unique Parameters

Each batch run must have unique `JobParameters` to create a new `JobInstance`. `SettlementService.launchJob()`:

```java
JobParameters params = new JobParametersBuilder()
    .addString("batchId", batchId)
    .addString("triggeredAt", Instant.now().toString())
    .toJobParameters();
jobLauncher.run(settlementJob, params);
```

Concurrent run detection: before launching, check `settlement_batches WHERE status = 'RUNNING'`. If exists, throw `BatchAlreadyRunningException` → REST layer returns 409.

### 8. Port Configuration

| Service | App Port | Management Port |
|---------|----------|-----------------|
| transaction-service | 8080 | 8081 |
| authorization-service | 8080 | 8082 |
| fraud-detection-service | 8080 | 8083 |
| notification-service | 8080 | 8084 |
| **settlement-service** | **8080** | **8085** |

---

## Flyway Migrations Overview

| Version | File | Description |
|---------|------|-------------|
| V1 | `V1__create_spring_batch_schema.sql` | Spring Batch 5.x metadata tables (PostgreSQL schema) |
| V2 | `V2__create_settlement_transactions.sql` | settlement_transactions + indexes on (status), (merchant_id, batch_id) |
| V3 | `V3__create_settlement_batches.sql` | settlement_batches + indexes on (status), (started_at DESC) |
| V4 | `V4__create_merchant_settlements.sql` | merchant_settlements + UNIQUE(batch_id, merchant_id) + indexes |
| V5 | `V5__create_processed_events.sql` | processed_events (consumer idempotency store) |

---

## Kafka Topics

| Topic | Direction | Event | Publisher | Consumer |
|-------|-----------|-------|-----------|---------|
| `transaction.authorized` | Inbound | `TransactionAuthorizedEvent` | authorization-service | settlement-service |
| `transaction.settled` | Outbound | `TransactionSettledEvent` | settlement-service | transaction-service, reporting-service |

---

## application.yml Key Settings

```yaml
server:
  port: 8080

management:
  server:
    port: 8085

spring:
  batch:
    job:
      enabled: false          # Don't run on startup
    jdbc:
      initialize-schema: never  # Flyway manages schema

  kafka:
    consumer:
      group-id: settlement-service-consumer
      auto-offset-reset: earliest
      enable-auto-commit: false
      isolation-level: read_committed

settlement:
  interchange-fee-rate: 0.02
  batch:
    chunk-size: 100
    cron: "0 59 23 * * *"
```

---

## Test Strategy

### Unit Tests (no Spring context)
- `InterchangeFeeServiceTest`: fee accuracy for multiple amounts, zero, rounding (HALF_UP), config validation
- `SettlementItemProcessorTest`: already-settled throws `AlreadySettledException`; valid item → correct fee/net
- `SettlementSchedulerTest`: `@MockBean SettlementService` — verify `launchJob()` called on `triggerDailySettlement()`
- `SettlementControllerTest` (`@WebMvcTest`): POST trigger → 202; GET report → 200/404; GET batch → 200/404; concurrent run → 409
- `GlobalExceptionHandlerTest`: `MerchantSettlementNotFoundException` → 404; validation → 400
- `TransactionAuthorizedListenerTest`: null eventId discarded; null transactionId discarded; duplicate skipped; valid → `settledTransactionRepository.save()` called; service throws → ack still called (finally block)
- Domain tests: all getters/setters for `SettlementTransaction`, `SettlementBatch`, `MerchantSettlement`, `SettledItem`
- `ExceptionTest`: `AlreadySettledException` constructors; `MerchantSettlementNotFoundException` message
- `AlreadySettledSkipPolicyTest`: `shouldSkip(AlreadySettledException, 0)` → true; `shouldSkip(RuntimeException, 0)` → false

### Integration Tests (Testcontainers PostgreSQL + @EmbeddedKafka)
All classes: `@SpringBootTest`, `@DirtiesContext`, `@ActiveProfiles("test")`, `@EmbeddedKafka(topics={"transaction.authorized","transaction.settled"})`

- `SettlementBatchIntegrationTest`: Quickstart scenarios 1.1–1.6
- `KafkaEventPublishingIntegrationTest`: Quickstart scenarios 2.1–2.3
- `MerchantReportIntegrationTest`: Quickstart scenarios 3.1–3.4
- Consumer idempotency scenarios C.1–C.2

### @SpringBatchTest Note
`@SpringBatchTest` injects `JobLauncherTestUtils` for launching jobs in tests and `JobRepositoryTestUtils` for cleaning up batch metadata between tests. Use alongside `@SpringBootTest`.

---

## Complexity Tracking

No constitution violations requiring justification.
