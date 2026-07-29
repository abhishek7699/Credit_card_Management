# Tasks: Settlement Service

**Input**: Design documents from `/specs/005-settlement-service/`

**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, contracts/ ✅, quickstart.md ✅

**Tests**: TDD with 100% instruction + branch coverage is mandatory per project constitution. Test tasks are included and MUST be written before implementation within each phase.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)
- Exact file paths included in all descriptions

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Initialize the `services/settlement-service/` Gradle project with all dependencies and base configuration.

- [X] T001 Add `include ':services:settlement-service'` to `settings.gradle` and create directory structure: `services/settlement-service/src/main/java/com/creditcard/settlement/`, `src/main/resources/db/migration/`, `src/test/java/com/creditcard/settlement/`, `src/test/resources/`
- [X] T002 Create `services/settlement-service/build.gradle` with plugins: `java`, `org.springframework.boot:3.2.5`, `io.spring.dependency-management:1.1.4`, `jacoco`; dependencies: `spring-boot-starter-batch`, `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-actuator`, `spring-boot-starter-validation`, `spring-kafka`, `flyway-core`, `postgresql`, `jackson-datatype-jsr310`, `logstash-logback-encoder:7.4`; test deps: `spring-boot-starter-test`, `spring-batch-test`, `spring-kafka-test`, `testcontainers:postgresql`, `awaitility`; JaCoCo 0.8.11 with 100% instruction + branch coverage gate excluding `SettlementServiceApplication`
- [X] T003 [P] Create `services/settlement-service/src/main/java/com/creditcard/settlement/SettlementServiceApplication.java` — `@SpringBootApplication @EnableScheduling` main class (no `@EnableBatchProcessing` — Spring Boot 3.x auto-configures Spring Batch)
- [X] T004 [P] Create `services/settlement-service/src/main/resources/application.yml` — `server.port: 8080`, `management.server.port: 8085`, `spring.batch.job.enabled: false`, `spring.batch.jdbc.initialize-schema: never`, `spring.kafka.consumer.group-id: settlement-service-consumer`, `spring.kafka.consumer.auto-offset-reset: earliest`, `spring.kafka.consumer.enable-auto-commit: false`, `spring.kafka.consumer.isolation-level: read_committed`, `spring.jpa.hibernate.ddl-auto: validate`, `spring.flyway.enabled: true`; custom section: `settlement.interchange-fee-rate: 0.02`, `settlement.batch.chunk-size: 100`, `settlement.batch.cron: "0 59 23 * * *"`
- [X] T005 [P] Create `services/settlement-service/src/test/resources/application-test.yml` — `spring.flyway.enabled: true`, `spring.jpa.hibernate.ddl-auto: none`; Testcontainers datasource override pattern (same as other services)
- [X] T006 [P] Create `services/settlement-service/Dockerfile` — multi-stage build: `gradle:8-jdk17` builder stage then `eclipse-temurin:17-jre-jammy` runtime stage (same pattern as other services)

**Checkpoint**: Project structure and Gradle build configuration in place — dependencies resolve

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Flyway migrations, JPA entities, repositories, Kafka config, channel abstraction, PAN masking filter, interchange fee service — all must be complete before any user story work begins.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

### Flyway Migrations

- [X] T007 Create `services/settlement-service/src/main/resources/db/migration/V1__create_spring_batch_schema.sql` — Spring Batch 5.x metadata tables using CockroachDB-compatible PostgreSQL DDL (copy from `org/springframework/batch/core/schema-postgresql.sql`): `BATCH_JOB_SEQ`, `BATCH_JOB_EXECUTION_SEQ`, `BATCH_STEP_EXECUTION_SEQ` sequences; `BATCH_JOB_INSTANCE`, `BATCH_JOB_EXECUTION`, `BATCH_JOB_EXECUTION_PARAMS`, `BATCH_JOB_EXECUTION_CONTEXT`, `BATCH_STEP_EXECUTION`, `BATCH_STEP_EXECUTION_CONTEXT` tables
- [X] T008 [P] Create `services/settlement-service/src/main/resources/db/migration/V2__create_settlement_transactions.sql` — `settlement_transactions` table: `transaction_id UUID PRIMARY KEY`, `merchant_id VARCHAR(100) NOT NULL`, `card_token VARCHAR(512) NOT NULL`, `masked_card_number VARCHAR(19) NOT NULL`, `amount DECIMAL(15,2) NOT NULL`, `currency_code VARCHAR(3) NOT NULL DEFAULT 'USD'`, `status VARCHAR(20) NOT NULL DEFAULT 'AUTHORIZED'`, `batch_id VARCHAR(100)`, `settlement_fee DECIMAL(15,2)`, `net_amount DECIMAL(15,2)`, `authorized_at TIMESTAMPTZ NOT NULL`, `settled_at TIMESTAMPTZ`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`; indexes on `(status)` and `(merchant_id, batch_id)`
- [X] T009 [P] Create `services/settlement-service/src/main/resources/db/migration/V3__create_settlement_batches.sql` — `settlement_batches` table: `batch_id VARCHAR(100) PRIMARY KEY`, `triggered_by VARCHAR(20) NOT NULL`, `status VARCHAR(20) NOT NULL DEFAULT 'RUNNING'`, `total_transactions_processed INT NOT NULL DEFAULT 0`, `total_gross_amount DECIMAL(15,2) NOT NULL DEFAULT 0`, `total_interchange_fees DECIMAL(15,2) NOT NULL DEFAULT 0`, `total_net_amount DECIMAL(15,2) NOT NULL DEFAULT 0`, `started_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `completed_at TIMESTAMPTZ`, `failure_reason TEXT`; indexes on `(status)` and `(started_at DESC)`
- [X] T010 [P] Create `services/settlement-service/src/main/resources/db/migration/V4__create_merchant_settlements.sql` — `merchant_settlements` table: `id UUID PRIMARY KEY DEFAULT gen_random_uuid()`, `batch_id VARCHAR(100) NOT NULL`, `merchant_id VARCHAR(100) NOT NULL`, `transaction_count INT NOT NULL`, `gross_amount DECIMAL(15,2) NOT NULL`, `total_interchange_fee DECIMAL(15,2) NOT NULL`, `net_settlement_amount DECIMAL(15,2) NOT NULL`, `currency_code VARCHAR(3) NOT NULL DEFAULT 'USD'`, `settlement_date DATE NOT NULL`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`; `UNIQUE (batch_id, merchant_id)`; indexes on `(merchant_id, batch_id)` and `(settlement_date DESC)`
- [X] T011 [P] Create `services/settlement-service/src/main/resources/db/migration/V5__create_processed_events.sql` — `processed_events` table: `event_key VARCHAR(255) PRIMARY KEY`, `processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`

### JPA Entities (Tests First)

- [X] T012 Write `services/settlement-service/src/test/java/com/creditcard/settlement/domain/SettlementTransactionTest.java` — all getters/setters, `@PrePersist` sets `createdAt`, default status `AUTHORIZED`; then implement `services/settlement-service/src/main/java/com/creditcard/settlement/domain/SettlementTransaction.java` — `@Entity @Table(name="settlement_transactions")`, `@Id UUID transactionId` (not generated — set from Kafka event), all columns per data-model.md, `@PrePersist` sets `createdAt`
- [X] T013 [P] Write `services/settlement-service/src/test/java/com/creditcard/settlement/domain/SettlementBatchTest.java` — getters/setters, default status `RUNNING`; then implement `services/settlement-service/src/main/java/com/creditcard/settlement/domain/SettlementBatch.java` — `@Entity @Table(name="settlement_batches")`, `@Id String batchId` (UUID string, assigned by service — no `@GeneratedValue`), all columns per data-model.md, `@PrePersist` sets `startedAt`
- [X] T014 [P] Write `services/settlement-service/src/test/java/com/creditcard/settlement/domain/MerchantSettlementTest.java` + `ProcessedEventTest.java` — all getters/setters; then implement `services/settlement-service/src/main/java/com/creditcard/settlement/domain/MerchantSettlement.java` (`@Entity @Table`, `@Id @GeneratedValue(strategy=UUID)`, `UNIQUE(batchId, merchantId)`) and `ProcessedEvent.java` (`@Id String eventKey`; constructor sets `processedAt`; no `@PrePersist` — native query bypasses JPA lifecycle, same pattern as notification-service)

### Repositories

- [X] T015 Create `services/settlement-service/src/main/java/com/creditcard/settlement/repository/SettlementTransactionRepository.java` — `JpaRepository<SettlementTransaction, UUID>`; `List<SettlementTransaction> findByStatus(String status)`; `Optional<SettlementTransaction> findByTransactionIdAndStatus(UUID txId, String status)`
- [X] T016 [P] Create `services/settlement-service/src/main/java/com/creditcard/settlement/repository/SettlementBatchRepository.java` — `JpaRepository<SettlementBatch, String>`; `Optional<SettlementBatch> findFirstByStatusOrderByStartedAtDesc(String status)`
- [X] T017 [P] Create `services/settlement-service/src/main/java/com/creditcard/settlement/repository/MerchantSettlementRepository.java` — `JpaRepository<MerchantSettlement, UUID>`; `Optional<MerchantSettlement> findByBatchIdAndMerchantId(String batchId, String merchantId)`; `@Modifying @Query` native UPSERT method for chunk-level accumulation
- [X] T018 [P] Create `services/settlement-service/src/main/java/com/creditcard/settlement/repository/ProcessedEventRepository.java` — `JpaRepository<ProcessedEvent, String>`; `@Modifying @Query(value="INSERT INTO processed_events(event_key, processed_at) VALUES (:key, NOW()) ON CONFLICT DO NOTHING", nativeQuery=true) int insertIfAbsent(@Param("key") String key)`

### Security & Logging

- [X] T019 Write `services/settlement-service/src/test/java/com/creditcard/settlement/config/logging/PanMaskingFilterTest.java` — PAN masked, non-PAN unaffected, null message NEUTRAL, null format NEUTRAL, multiple PANs masked; then implement `services/settlement-service/src/main/java/com/creditcard/settlement/config/logging/PanMaskingFilter.java` — Logback `TurboFilter`; regex `\b\d{13,19}\b` → `[MASKED]`; NEUTRAL on null format; also create `services/settlement-service/src/main/resources/logback-spring.xml` with `PanMaskingFilter` registered and JSON encoder

### Kafka Configuration

- [X] T020 Create `services/settlement-service/src/main/java/com/creditcard/settlement/config/KafkaConsumerConfig.java` — single `@Bean ConcurrentKafkaListenerContainerFactory<String, TransactionAuthorizedEvent>` named `txAuthorizedContainerFactory`; `AckMode.MANUAL_IMMEDIATE`; `AUTO_OFFSET_RESET_CONFIG = "earliest"` set programmatically (critical for `@EmbeddedKafka` reliability); `USE_TYPE_HEADERS = false`; `VALUE_DEFAULT_TYPE = TransactionAuthorizedEvent.class.getName()`
- [X] T021 [P] Create `services/settlement-service/src/main/java/com/creditcard/settlement/config/KafkaProducerConfig.java` — `@Bean KafkaTemplate<String, TransactionSettledEvent>` with `JsonSerializer`; producer props: `acks=all`, `retries=3`, `enable.idempotence=true`
- [X] T022 [P] Create `services/settlement-service/src/main/java/com/creditcard/settlement/config/BatchConfig.java` — `@Configuration @ConfigurationProperties(prefix="settlement")` to bind `interchangeFeeRate: BigDecimal`, `batch.chunkSize: int`, `batch.cron: String`; validate `@DecimalMin("0.00") @DecimalMax("0.50") interchangeFeeRate`

### Kafka Event POJOs

- [X] T023 Create `services/settlement-service/src/main/java/com/creditcard/settlement/kafka/event/TransactionAuthorizedEvent.java` — `@JsonIgnoreProperties(ignoreUnknown=true)` top-level POJO with `eventId, eventType, sourceService, traceId, occurredAt (Instant)`; nested static `Payload` class with `transactionId (UUID), merchantId (String), cardToken (String), maskedCardNumber (String), amount (BigDecimal), currencyCode (String), authorizedAt (Instant)`
- [X] T024 [P] Create `services/settlement-service/src/main/java/com/creditcard/settlement/kafka/event/TransactionSettledEvent.java` — `@JsonIgnoreProperties(ignoreUnknown=true)` POJO with `eventId (UUID.randomUUID()), eventType="TransactionSettled", sourceService="settlement-service", traceId, occurredAt`; nested `Payload` with `transactionId, merchantId, batchId, grossAmount (BigDecimal), interchangeFee (BigDecimal), netAmount (BigDecimal), currencyCode, settlementDate (LocalDate), settledAt (Instant)`

### Model

- [X] T025 [P] Write `services/settlement-service/src/test/java/com/creditcard/settlement/model/SettledItemTest.java` — getters/setters; then implement `services/settlement-service/src/main/java/com/creditcard/settlement/model/SettledItem.java` — non-persisted POJO carrying `SettlementTransaction`, `BigDecimal grossAmount`, `BigDecimal interchangeFee`, `BigDecimal netAmount`; used as ItemProcessor output

### Exceptions

- [X] T026 [P] Write `services/settlement-service/src/test/java/com/creditcard/settlement/exception/ExceptionTest.java` — verify constructors and messages for both exceptions; then implement `services/settlement-service/src/main/java/com/creditcard/settlement/exception/AlreadySettledException.java` (extends RuntimeException, takes transactionId String) and `MerchantSettlementNotFoundException.java` (extends RuntimeException, takes merchantId + batchId)

### InterchangeFeeService

- [X] T027 Write `services/settlement-service/src/test/java/com/creditcard/settlement/service/InterchangeFeeServiceTest.java` — unit tests for: `calculateFee(100.00)→2.00`, `calculateFee(150.75)→3.02` (HALF_UP rounding), `calculateFee(0.01)→0.00`, `calculateFee(10000.00)→200.00`; startup validation rejects rate < 0 or > 0.5
- [X] T028 Implement `services/settlement-service/src/main/java/com/creditcard/settlement/service/InterchangeFeeService.java` — `@Service`; inject `BigDecimal interchangeFeeRate` from `@ConfigurationProperties`; `public BigDecimal calculateFee(BigDecimal amount)` returns `amount.multiply(rate).setScale(2, RoundingMode.HALF_UP)`; `@PostConstruct` validates rate is non-null and in `[0.00, 0.50]`

**Checkpoint**: All entities, repositories, Kafka config, PAN masking, and interchange fee service in place — user stories can now proceed

---

## Phase 3: User Story 1 — End-of-Day Batch Settlement (Priority: P1) 🎯 MVP

**Goal**: Consume `TransactionAuthorizedEvent` from Kafka to populate local transaction store, then run a scheduled batch job that settles all AUTHORIZED transactions by calculating fees, updating their status to SETTLED, and aggregating per-merchant settlement totals. Fully idempotent: already-settled transactions are silently skipped on re-run.

**Independent Test**: Publish 5 `TransactionAuthorizedEvent` for 3 merchants → trigger batch → assert all 5 transactions SETTLED with correct fee deductions, 3 `merchant_settlements` records with correct aggregates, batch completes with status COMPLETED; re-trigger batch → still exactly 5 SETTLED records, second batch shows 0 processed.

> **TDD: Write tests FIRST. All test tasks MUST be written before implementation tasks and MUST fail (RED) initially.**

### Tests for User Story 1

- [X] T029 [US1] Write `services/settlement-service/src/test/java/com/creditcard/settlement/batch/processor/SettlementItemProcessorTest.java` — unit tests (no Spring context): already-SETTLED transaction throws `AlreadySettledException`; valid AUTHORIZED transaction → returns `SettledItem` with correct `interchangeFee` and `netAmount`; null `merchantId` on transaction is handled by skip policy (processor itself does fee calculation)
- [X] T030 [P] [US1] Write `services/settlement-service/src/test/java/com/creditcard/settlement/batch/AlreadySettledSkipPolicyTest.java` — `shouldSkip(AlreadySettledException, 0) → true`; `shouldSkip(RuntimeException, 0) → false`; `shouldSkip(IllegalStateException, 999) → false`
- [X] T031 [P] [US1] Write `services/settlement-service/src/test/java/com/creditcard/settlement/kafka/TransactionAuthorizedListenerTest.java` — unit tests (`@ExtendWith(MockitoExtension.class)` `@MockitoSettings(strictness=LENIENT)`): null eventId discarded (insertIfAbsent never called); null transactionId discarded; null payload discarded; duplicate event (insertIfAbsent returns 0) → save never called; valid event → `settlementTransactionRepository.save()` called; service throws RuntimeException → ack still called (finally block)
- [X] T032 [US1] Create `services/settlement-service/src/test/java/com/creditcard/settlement/integration/TestKafkaConfig.java` — `@TestConfiguration`; `@Bean(name="txAuthorizedKafkaTemplate") KafkaTemplate<String, TransactionAuthorizedEvent>` with `JsonSerializer` wired to `${spring.embedded.kafka.brokers}` via `@Value`
- [X] T033 [US1] Write `services/settlement-service/src/test/java/com/creditcard/settlement/integration/SettlementBatchIntegrationTest.java` — `@SpringBatchTest @SpringBootTest @EmbeddedKafka(topics={"transaction.authorized","transaction.settled"}) @Testcontainers @ActiveProfiles("test") @DirtiesContext`; covers quickstart scenarios 1.1 (5 txns, 3 merchants → all SETTLED, correct aggregates), 1.2 (zero AUTHORIZED → batch COMPLETED, 0 records), 1.3 (idempotent re-run → second batch 0 processed), 1.4 (null merchantId txn skipped → WARN logged), 1.5 (zero-amount txn not settled); use `JobLauncherTestUtils` to launch job; use `@Autowired JobRepositoryTestUtils` to clean batch metadata in `@BeforeEach`

### Implementation for User Story 1

- [X] T034 [US1] Create `services/settlement-service/src/main/java/com/creditcard/settlement/batch/AlreadySettledSkipPolicy.java` — implements `SkipPolicy`; `shouldSkip(Throwable t, long skipCount)` returns `t instanceof AlreadySettledException`
- [X] T035 [US1] Create `services/settlement-service/src/main/java/com/creditcard/settlement/batch/reader/AuthorizedTransactionReader.java` — `@Component`; factory method returning `@StepScope @Bean JdbcCursorItemReader<SettlementTransaction>` named `settlementTransactionReader`; SQL: `SELECT transaction_id, merchant_id, card_token, masked_card_number, amount, currency_code, status, batch_id, settlement_fee, net_amount, authorized_at, settled_at, created_at FROM settlement_transactions WHERE status = 'AUTHORIZED' ORDER BY authorized_at`; `RowMapper<SettlementTransaction>` maps all columns
- [X] T036 [US1] Create `services/settlement-service/src/main/java/com/creditcard/settlement/batch/processor/SettlementItemProcessor.java` — implements `ItemProcessor<SettlementTransaction, SettledItem>`; if `transaction.getStatus().equals("SETTLED")` throw `AlreadySettledException`; inject `InterchangeFeeService`; calculate fee + netAmount; return new `SettledItem(transaction, amount, fee, net)`
- [X] T037 [US1] Create `services/settlement-service/src/main/java/com/creditcard/settlement/batch/writer/SettlementItemWriter.java` — implements `ItemWriter<SettledItem>`; in `write(Chunk<? extends SettledItem>)`: (1) batch-update `settlement_transactions` to SETTLED+fee+net+settled_at+batch_id; (2) accumulate per-merchant totals in `Map<String, MerchantAccumulator>`; (3) UPSERT each merchant accumulator into `merchant_settlements` via native SQL `INSERT ... ON CONFLICT (batch_id, merchant_id) DO UPDATE SET ...`; (4) for each settled item publish `TransactionSettledEvent` via `KafkaTemplate` (catch Kafka failure, log WARN, do NOT rollback); inject `batchId` via `@StepScope @Value("#{jobParameters['batchId']}")`
- [X] T038 [US1] Create `services/settlement-service/src/main/java/com/creditcard/settlement/batch/SettlementJobConfig.java` — `@Configuration`; `@Bean Job settlementJob(Step settlementStep, JobRepository jobRepository)` using `JobBuilder("settlementJob", jobRepository).start(settlementStep).build()`; `@Bean Step settlementStep(...)` using `StepBuilder("settlementStep", jobRepository).<SettlementTransaction, SettledItem>chunk(chunkSize, txManager).reader(reader).processor(processor).writer(writer).faultTolerant().skipPolicy(new AlreadySettledSkipPolicy()).build()`; `@Bean @StepScope` reader delegating to `AuthorizedTransactionReader`
- [X] T039 [US1] Create `services/settlement-service/src/main/java/com/creditcard/settlement/kafka/TransactionAuthorizedListener.java` — `@Component`; `@KafkaListener(topics="transaction.authorized", containerFactory="txAuthorizedContainerFactory", groupId="${spring.kafka.consumer.group-id}")`; method: null payload guard → log WARN + ack + return; null eventId guard; null transactionId guard; idempotency check `insertIfAbsent("settlement:TransactionAuthorized:{eventId}")` → if 0 skip; build `SettlementTransaction` and save; `finally { ack.acknowledge() }`
- [X] T040 [US1] Create `services/settlement-service/src/main/java/com/creditcard/settlement/service/SettlementService.java` — `@Service`; inject `JobLauncher`, `Job settlementJob`, `SettlementBatchRepository`, `MerchantSettlementRepository`; `launchJob(String triggeredBy)`: check for RUNNING batch (if exists, throw `BatchAlreadyRunningException`), create+save `SettlementBatch` record, build `JobParameters` with `batchId` + `triggeredAt` + `triggeredBy`, call `jobLauncher.run(settlementJob, params)`; `findBatch(String batchId)`: `findById` or throw; `findMerchantSettlement(String merchantId, String batchId)`: `findByBatchIdAndMerchantId` or throw `MerchantSettlementNotFoundException`
- [X] T041 [US1] Create `services/settlement-service/src/main/java/com/creditcard/settlement/scheduler/SettlementScheduler.java` — `@Component`; `@Scheduled(cron="${settlement.batch.cron}", zone="UTC")`; `triggerDailySettlement()` calls `settlementService.launchJob("SCHEDULER")`; catch and log any exception without rethrowing (scheduler must not crash)
- [X] T042 [US1] Run `./gradlew :services:settlement-service:test jacocoTestReport jacocoTestCoverageVerification` — all US1 tests pass (batch integration, listener unit, processor unit, skip policy); JaCoCo 100% instruction + branch gate passes for US1 production classes

**Checkpoint**: US1 fully functional — authorized transactions settled; merchant aggregates correct; idempotency enforced; all quickstart 1.x scenarios pass

---

## Phase 4: User Story 2 — Settlement Event Publishing (Priority: P2)

**Goal**: Each settled transaction produces exactly one `TransactionSettledEvent` on the `transaction.settled` Kafka topic with correct payload (transactionId, merchantId, batchId, grossAmount, interchangeFee, netAmount, currencyCode, settlementDate, settledAt). No events on re-run for already-settled transactions. No event on failed settlement.

**Independent Test**: Publish 5 `TransactionAuthorizedEvent`, trigger batch, consume from `transaction.settled` → assert exactly 5 events with correct payloads; re-run batch → 0 additional events.

> **TDD: Write tests FIRST.**

### Tests for User Story 2

- [X] T043 [US2] Update `services/settlement-service/src/test/java/com/creditcard/settlement/integration/TestKafkaConfig.java` — add `@Bean(name="settledEventConsumerTemplate") KafkaTemplate<String, TransactionSettledEvent>` and a `@Bean KafkaMessageListenerContainer` or use `ConsumerFactory` to assert received events; add `@Bean(name="settledEventsReceived") BlockingQueue<ConsumerRecord<String, TransactionSettledEvent>>` for asserting events in tests
- [X] T044 [US2] Write `services/settlement-service/src/test/java/com/creditcard/settlement/integration/KafkaEventPublishingIntegrationTest.java` — `@SpringBatchTest @SpringBootTest @EmbeddedKafka @Testcontainers @ActiveProfiles("test") @DirtiesContext`; covers quickstart scenarios 2.1 (5 txns → exactly 5 `TransactionSettledEvent` with correct payload fields including `interchangeFee` and `netAmount`), 2.2 (re-run → 0 additional events from `transaction.settled`), 2.3 (batch with all already-SETTLED transactions → 0 events); use `Awaitility` to wait for events on queue

### Implementation for User Story 2

- [X] T045 [US2] Verify `services/settlement-service/src/main/java/com/creditcard/settlement/batch/writer/SettlementItemWriter.java` — confirm `TransactionSettledEvent` is fully populated with all contract fields (transactionId, merchantId, batchId, grossAmount, interchangeFee, netAmount, currencyCode, settlementDate=LocalDate.now(UTC), settledAt, eventId=UUID.randomUUID(), sourceService="settlement-service"); if incomplete, complete the event builder; ensure Kafka publish is inside the write loop per settled item, not batched
- [X] T046 [US2] Run `./gradlew :services:settlement-service:test jacocoTestReport jacocoTestCoverageVerification` — all US2 Kafka event tests pass; JaCoCo 100% instruction + branch gate passes for US2 classes

**Checkpoint**: US1 + US2 both functional — transactions settled AND events published to Kafka

---

## Phase 5: User Story 3 — Merchant Settlement Report (Priority: P3)

**Goal**: REST API at `/internal/settlement` exposing: `POST /trigger` (manual batch trigger, returns 202 or 409 if already running), `GET /reports/{merchantId}/{batchId}` (merchant settlement report, 200 or 404), `GET /batches/{batchId}` (batch status, 200 or 404).

**Independent Test**: Run a batch with 8 AUTHORIZED transactions for 2 merchants, then `GET /internal/settlement/reports/{merchantId}/{batchId}` → assert correct transactionCount, grossAmount, totalInterchangeFee, netSettlementAmount; `GET /internal/settlement/batches/{batchId}` → asserts COMPLETED status with totals.

> **TDD: Write tests FIRST.**

### Tests for User Story 3

- [X] T047 [US3] Write `services/settlement-service/src/test/java/com/creditcard/settlement/web/SettlementControllerTest.java` — `@WebMvcTest(SettlementController.class)` with `@MockBean SettlementService`; tests: `POST /internal/settlement/trigger` → 202 with `BatchTriggerResponse` body; `POST /internal/settlement/trigger` when service throws `BatchAlreadyRunningException` → 409 with `{"error":"...", "runningBatchId":"..."}`; `GET /internal/settlement/reports/M1/batch-001` returns 200 with `MerchantSettlementResponse`; `GET /internal/settlement/reports/missing/batch` → 404; `GET /internal/settlement/batches/batch-001` → 200; `GET /internal/settlement/batches/missing` → 404
- [X] T048 [P] [US3] Write `services/settlement-service/src/test/java/com/creditcard/settlement/web/GlobalExceptionHandlerTest.java` — `@WebMvcTest` or standalone `MockMvc`; verify `MerchantSettlementNotFoundException` → 404 JSON `{"error":"..."}`, `MethodArgumentNotValidException` → 400 JSON `{"errors":[...]}`, `BatchAlreadyRunningException` → 409 JSON
- [X] T049 [US3] Write `services/settlement-service/src/test/java/com/creditcard/settlement/integration/MerchantReportIntegrationTest.java` — `@SpringBatchTest @SpringBootTest(webEnvironment=RANDOM_PORT) @EmbeddedKafka @Testcontainers @ActiveProfiles("test") @DirtiesContext`; covers quickstart scenarios 3.1 (report available after batch, correct values), 3.2 (per-merchant isolation — GET for M1 returns only M1 data), 3.3 (404 for unknown merchant/batch), 3.4 (report immutable after re-run — same batchId values unchanged); use `TestRestTemplate` for REST calls
- [X] T050 [P] [US3] Write `services/settlement-service/src/test/java/com/creditcard/settlement/service/SettlementServiceTest.java` — `@ExtendWith(MockitoExtension.class)`; unit tests: `launchJob` creates `SettlementBatch`, calls `jobLauncher.run()`; `launchJob` when RUNNING batch exists → throws `BatchAlreadyRunningException`; `findBatch` not found → throws `RuntimeException`; `findMerchantSettlement` not found → throws `MerchantSettlementNotFoundException`; `findMerchantSettlement` found → returns entity; `SettlementScheduler.triggerDailySettlement` calls `launchJob("SCHEDULER")`; exception in scheduler is caught and not rethrown

### Implementation for User Story 3

- [X] T051 [US3] Create `services/settlement-service/src/main/java/com/creditcard/settlement/web/dto/BatchTriggerResponse.java` and `MerchantSettlementResponse.java` — plain POJOs with all fields from `contracts/api.md` (`BatchTriggerResponse`: batchId, status, triggeredBy, startedAt; `MerchantSettlementResponse`: merchantId, batchId, transactionCount, grossAmount, totalInterchangeFee, netSettlementAmount, currencyCode, settlementDate)
- [X] T052 [US3] Create `services/settlement-service/src/main/java/com/creditcard/settlement/exception/BatchAlreadyRunningException.java` — extends RuntimeException; constructor takes `runningBatchId String`; getter `getRunningBatchId()`
- [X] T053 [US3] Create `services/settlement-service/src/main/java/com/creditcard/settlement/web/GlobalExceptionHandler.java` — `@RestControllerAdvice`; handle `MerchantSettlementNotFoundException` → 404 `{"error": "...message..."}`; handle `BatchAlreadyRunningException` → 409 `{"error": "Settlement batch is already running", "runningBatchId": "..."}`; handle `MethodArgumentNotValidException` → 400 `{"errors":[{"field":"...","message":"..."}]}` for field errors only
- [X] T054 [US3] Add `findBatch(String batchId)` and `findMerchantSettlement(String merchantId, String batchId)` to `services/settlement-service/src/main/java/com/creditcard/settlement/service/SettlementService.java`; also fix `launchJob` to use `BatchAlreadyRunningException` (with runningBatchId) instead of generic exception
- [X] T055 [US3] Create `services/settlement-service/src/main/java/com/creditcard/settlement/web/SettlementController.java` — `@RestController @RequestMapping("/internal/settlement")`; `@PostMapping("/trigger")` → call `settlementService.launchJob("MANUAL")` → return 202 with `BatchTriggerResponse`; `@GetMapping("/reports/{merchantId}/{batchId}")` → call `settlementService.findMerchantSettlement(...)` → return 200 with `MerchantSettlementResponse`; `@GetMapping("/batches/{batchId}")` → call `settlementService.findBatch(...)` → return 200 with `BatchTriggerResponse`; all exceptions delegated to `GlobalExceptionHandler`
- [X] T056 [US3] Run `./gradlew :services:settlement-service:test jacocoTestReport jacocoTestCoverageVerification` — all US3 REST tests pass; JaCoCo 100% instruction + branch gate passes for all US3 classes

**Checkpoint**: All three user stories independently functional and tested

---

## Phase 6: Polish & Infrastructure

**Purpose**: Docker/k8s infrastructure and final full coverage gate verification

- [X] T057 [P] Add `settlement-service` to `docker-compose.yml` — image from `services/settlement-service/Dockerfile`, ports `8080:8080` and `8085:8085`, environment variables for `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `SPRING_KAFKA_BOOTSTRAP_SERVERS`, `SETTLEMENT_INTERCHANGE_FEE_RATE=0.02`, `SETTLEMENT_BATCH_CRON`, depends_on: kafka + cockroachdb
- [X] T058 [P] Create `k8s/settlement-service/deployment.yaml` — `Deployment` with 2 replicas, container image placeholder, env vars for DB and Kafka, liveness probe on port 8085 `/actuator/health/liveness`, readiness probe on port 8085 `/actuator/health/readiness`, resource limits (256Mi/500m)
- [X] T059 [P] Create `k8s/settlement-service/service.yaml` — `ClusterIP` Service on port 8080, selector matching settlement-service deployment labels
- [X] T060 Run `./gradlew :services:settlement-service:test jacocoTestReport jacocoTestCoverageVerification` — full suite: all US1+US2+US3+foundational tests pass; JaCoCo 100% instruction + branch coverage gate passes across all production classes; zero exclusions (except `SettlementServiceApplication`)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately
- **Foundational (Phase 2)**: Depends on Phase 1 — BLOCKS all user stories
- **US1 (Phase 3)**: Depends on Foundational — most complex phase (batch infrastructure)
- **US2 (Phase 4)**: Depends on US1 (uses `SettlementItemWriter` Kafka publishing built in US1)
- **US3 (Phase 5)**: Depends on Foundational + US1 (`SettlementService.launchJob` built in US1); independent of US2
- **Polish (Phase 6)**: Depends on all user stories complete

### User Story Dependencies

- **US1 (P1)**: Independent after Foundational — start here for MVP
- **US2 (P2)**: Depends on US1 (SettlementItemWriter already publishes events; US2 adds tests + verification)
- **US3 (P3)**: Depends on US1's SettlementService; independent of US2 (REST path)

### Within Each User Story

- Write tests (RED) → implement AlreadySettledSkipPolicy / reader → processor → writer → job config → listener → service → scheduler → run coverage gate (GREEN)

### Parallel Opportunities

- T003/T004/T005/T006 can run in parallel (Phase 1)
- T008/T009/T010/T011 can run in parallel (Flyway migrations)
- T013/T014 can run in parallel (entity tests+impl)
- T016/T017/T018 can run in parallel (repositories)
- T020/T021/T022 can run in parallel (Kafka + Batch configs)
- T023/T024 can run in parallel (event POJOs)
- T025/T026 can run in parallel (model + exceptions)
- T029/T030/T031 can run in parallel (US1 unit tests)
- T047/T048 can run in parallel (US3 unit tests)
- T057/T058/T059 can run in parallel (Phase 6 infrastructure)

---

## Parallel Example: User Story 1

```bash
# These can run in parallel once Phase 2 is complete:
Task T029: SettlementItemProcessorTest.java  [unit test]
Task T030: AlreadySettledSkipPolicyTest.java  [unit test]
Task T031: TransactionAuthorizedListenerTest.java  [unit test]

# Sequential (T032 then T033 — test infra then integration test):
Task T032: TestKafkaConfig.java
Task T033: SettlementBatchIntegrationTest.java

# Then implementation (sequential — each builds on prior):
Task T034: AlreadySettledSkipPolicy.java
Task T035: AuthorizedTransactionReader.java
Task T036: SettlementItemProcessor.java
Task T037: SettlementItemWriter.java  [depends T036]
Task T038: SettlementJobConfig.java  [depends T035/T036/T037]
Task T039: TransactionAuthorizedListener.java
Task T040: SettlementService.java
Task T041: SettlementScheduler.java  [depends T040]
Task T042: Run coverage gate
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (CRITICAL — blocks all stories)
3. Complete Phase 3: User Story 1 (T029–T042)
4. **STOP and VALIDATE**: All 5 quickstart 1.x scenarios pass; JaCoCo gate green
5. Demo: publish events → trigger batch → verify merchant settlements in DB

### Incremental Delivery

1. Phase 1 + Phase 2 → Infrastructure + Kafka + Spring Batch configured
2. Phase 3 (US1) → Batch settlement MVP ✅ Demo-able
3. Phase 4 (US2) → Kafka event publishing added ✅ Downstream-ready
4. Phase 5 (US3) → REST trigger + settlement reports ✅ Feature complete
5. Phase 6 → Docker/k8s + final coverage gate ✅ Release ready

---

## Notes

- `[P]` = different files, no incomplete dependencies — can run in parallel
- `[US?]` label maps task to specific user story for traceability
- **CRITICAL: No `@EnableBatchProcessing`** — Spring Boot 3.x auto-configures `JobRepository`/`JobLauncher`; adding this annotation disables `BatchAutoConfiguration` and breaks the setup
- **CRITICAL: `spring.batch.jdbc.initialize-schema=never`** — Flyway V1 owns Spring Batch metadata DDL; never let Spring Batch auto-create its tables
- **CRITICAL: `spring.batch.job.enabled=false`** — prevents auto-run on startup; job triggered only by scheduler or REST endpoint
- **CRITICAL: `AUTO_OFFSET_RESET_CONFIG = "earliest"` MUST be set programmatically** in `KafkaConsumerConfig` (not just YAML) for `@EmbeddedKafka` reliability
- `@SpringBatchTest` injects `JobLauncherTestUtils` and `JobRepositoryTestUtils` — use `JobRepositoryTestUtils.removeJobExecutions()` in `@BeforeEach` to clean Spring Batch metadata between tests
- `@DirtiesContext` required on every integration test class (Spring Batch JobRepository and Kafka container lifecycle)
- `SettlementItemWriter.write()` runs inside Spring Batch's chunk transaction — DB updates commit before Kafka publish; Kafka failure is caught/logged and does NOT roll back the DB commit
- `UNIQUE(batch_id, merchant_id)` constraint on `merchant_settlements` enables idempotent UPSERT during chunk replay
- `ProcessedEvent.processedAt` set in constructor, not `@PrePersist` — native query bypasses JPA lifecycle (same pattern as notification-service)
- Each batch run requires unique `JobParameters`: use `batchId` (UUID) + `triggeredAt` (ISO timestamp string) to allow multiple runs per day
- V1 migration uses verbatim Spring Batch PostgreSQL schema DDL — CockroachDB 23+ supports `CREATE SEQUENCE` and `NEXTVAL` without modification
