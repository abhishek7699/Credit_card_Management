---
description: "Task list for authorization-service implementation"
---

# Tasks: Authorization Service

**Input**: Design documents from `/specs/002-authorization-service/`

**Prerequisites**: plan.md ✅ | spec.md ✅ | research.md ✅ | data-model.md ✅ | contracts/ ✅

**TDD Mandate**: Tests are MANDATORY per constitution Principle II and spec SC-007.
Every class requires: (1) write test → (2) confirm RED → (3) implement → (4) confirm GREEN.
No implementation task may begin until its test task is complete and confirmed failing.

**Organization**: Tasks organized by user story. Each story is independently testable.

## Format: `[ID] [P?] [Story?] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)
- Each task includes exact file path

---

## Phase 1: Setup

**Purpose**: Initialize the Gradle subproject, create the package skeleton, and wire up
the Spring Boot entry point. No user story work begins until this phase is complete.

- [X] T001 Register `:services:authorization-service` subproject in root `settings.gradle` (add `include ':services:authorization-service'` alongside existing `:services:transaction-service`)
- [X] T002 Create `services/authorization-service/build.gradle` with all dependencies per plan.md: spring-boot-starter-data-jpa, spring-kafka, spring-retry, spring-boot-starter-aop, spring-boot-starter-actuator, flyway-core, postgresql driver, micrometer-tracing-bridge-brave, zipkin-reporter-brave, logstash-logback-encoder:7.4, testcontainers (postgresql + kafka + junit-jupiter 1.19.7), spring-kafka-test, spring-boot-starter-test, mockito-core; configure JaCoCo 0.8.11 with 100% instruction + branch gate excluding `AuthorizationServiceApplication.class`
- [X] T003 [P] Create `services/authorization-service/src/main/java/com/creditcard/authorization/AuthorizationServiceApplication.java` Spring Boot entry point with `@SpringBootApplication` and standard `main()` method
- [X] T004 [P] Create `services/authorization-service/src/main/resources/application.yml` with: `spring.application.name=authorization-service`, datasource placeholders (`${SPRING_DATASOURCE_URL}` etc.), Kafka consumer group `authorization-service-consumer` with `enable-auto-commit: false`, `isolation-level: read_committed`, `acks: all`, `enable.idempotence: true`, Flyway enabled, management port 8082, actuator health probes enabled
- [X] T005 [P] Create `services/authorization-service/src/main/resources/application-local.yml` with Docker Compose local values: CockroachDB JDBC URL `jdbc:postgresql://localhost:26257/defaultdb?sslmode=disable`, Kafka `localhost:9092`
- [X] T006 [P] Create `services/authorization-service/src/test/resources/application-test.yml` with Testcontainers overrides: `spring.jpa.hibernate.ddl-auto=create-drop`, Flyway enabled, Kafka `@EmbeddedKafka` bootstrap placeholder, `spring.datasource.url` left for Testcontainers to override via `@DynamicPropertySource`
- [X] T007 Verify `./gradlew :services:authorization-service:compileJava` succeeds with no errors before proceeding to Phase 2

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: All domain enums, JPA entities, Flyway migrations, repositories, event POJOs,
Kafka config, idempotency service, and logging infrastructure that every user story depends
on. No user story work begins until this phase is complete.

**⚠️ CRITICAL**: Complete each task's test → RED → implement → GREEN cycle before moving
to the next task. Do not skip ahead.

### Domain Enums

- [X] T008 [P] Write failing unit test for `CardAccountStatus` enum (ACTIVE, BLOCKED, EXPIRED; verify `isActive()` returns true only for ACTIVE; confirm RED) at `services/authorization-service/src/test/java/com/creditcard/authorization/domain/CardAccountStatusTest.java`
- [X] T009 Implement `CardAccountStatus` enum with `isActive()` helper at `services/authorization-service/src/main/java/com/creditcard/authorization/domain/CardAccountStatus.java`; confirm GREEN
- [X] T010 [P] Write failing unit test for `AuthorizationOutcome` enum (AUTHORIZED, DECLINED, FAILED; verify `isApproved()` returns true only for AUTHORIZED; confirm RED) at `services/authorization-service/src/test/java/com/creditcard/authorization/domain/AuthorizationOutcomeTest.java`
- [X] T011 Implement `AuthorizationOutcome` enum with `isApproved()` at `services/authorization-service/src/main/java/com/creditcard/authorization/domain/AuthorizationOutcome.java`; confirm GREEN
- [X] T012 [P] Write failing unit test for `ReasonCode` enum (INVALID_CARD, CARD_NOT_ACTIVE, INSUFFICIENT_CREDIT, INVALID_EVENT_PAYLOAD, INTERNAL_ERROR; all 5 values present; confirm RED) at `services/authorization-service/src/test/java/com/creditcard/authorization/domain/ReasonCodeTest.java`
- [X] T013 Implement `ReasonCode` enum at `services/authorization-service/src/main/java/com/creditcard/authorization/domain/ReasonCode.java`; confirm GREEN

### JPA Domain Entities

- [X] T014 Write failing unit test for `CardAccount` entity (verify all getters/setters: id, cardToken, status, creditLimit, availableCredit, currencyCode, createdAt, updatedAt, version; verify `onCreate()` sets both timestamps; verify `onUpdate()` updates only updatedAt; confirm RED) at `services/authorization-service/src/test/java/com/creditcard/authorization/domain/CardAccountTest.java`
- [X] T015 Implement `CardAccount` JPA entity with `@Entity`, `@Table(name="card_accounts")`, `@Id` UUID, `@Version` Long, `@PrePersist`/`@PreUpdate` lifecycle hooks, all columns matching `V1__create_card_accounts.sql` schema at `services/authorization-service/src/main/java/com/creditcard/authorization/domain/CardAccount.java`; confirm GREEN
- [X] T016 [P] Write failing unit test for `AuthorizationRecord` entity (all fields: id, transactionId, cardToken, requestedAmount, currencyCode, outcome, reasonCode, decidedAt; verify no-arg constructor + builder/setters; confirm RED) at `services/authorization-service/src/test/java/com/creditcard/authorization/domain/AuthorizationRecordTest.java`
- [X] T017 Implement `AuthorizationRecord` JPA entity with `@Entity`, `@Table(name="authorization_records")`, all columns matching `V2__create_authorization_records.sql` at `services/authorization-service/src/main/java/com/creditcard/authorization/domain/AuthorizationRecord.java`; confirm GREEN

### Flyway Migrations

- [X] T018 [P] Create `services/authorization-service/src/main/resources/db/migration/V1__create_card_accounts.sql` with full DDL: UUID PK, card_token UNIQUE, status CHECK ('ACTIVE','BLOCKED','EXPIRED'), credit_limit/available_credit DECIMAL(15,4) with CHECK constraints, currency_code CHAR(3), timestamps, version BIGINT; INDEX on card_token
- [X] T019 [P] Create `services/authorization-service/src/main/resources/db/migration/V2__create_authorization_records.sql` with full DDL: UUID PK, transaction_id UUID NOT NULL, card_token, requested_amount, currency_code, outcome CHECK ('AUTHORIZED','DECLINED','FAILED'), reason_code nullable, decided_at; INDEX on transaction_id
- [X] T020 [P] Create `services/authorization-service/src/main/resources/db/migration/V3__create_processed_events.sql` with full DDL: event_key VARCHAR(255) PRIMARY KEY, processed_at TIMESTAMPTZ NOT NULL DEFAULT now()

### Repositories

- [X] T021 Write failing unit test for `CardAccountRepository` (verify `findByCardToken(token)` returns `Optional<CardAccount>` when present and empty when absent; use `@DataJpaTest` + Testcontainers PostgreSQL; confirm RED) at `services/authorization-service/src/test/java/com/creditcard/authorization/repository/CardAccountRepositoryTest.java`
- [X] T022 Implement `CardAccountRepository` extending `JpaRepository<CardAccount, UUID>` with `Optional<CardAccount> findByCardToken(String cardToken)` at `services/authorization-service/src/main/java/com/creditcard/authorization/repository/CardAccountRepository.java`; confirm GREEN
- [X] T023 [P] Write failing unit test for `AuthorizationRecordRepository` (verify save persists record; verify `findByTransactionId(id)` returns matching records; `@DataJpaTest` + Testcontainers; confirm RED) at `services/authorization-service/src/test/java/com/creditcard/authorization/repository/AuthorizationRecordRepositoryTest.java`
- [X] T024 Implement `AuthorizationRecordRepository` extending `JpaRepository<AuthorizationRecord, UUID>` with `List<AuthorizationRecord> findByTransactionId(UUID transactionId)` at `services/authorization-service/src/main/java/com/creditcard/authorization/repository/AuthorizationRecordRepository.java`; confirm GREEN
- [X] T025 [P] Write failing unit test for `ProcessedEventRepository` (verify `insertIfAbsent(key)` returns 1 for new key, returns 0 for duplicate key; `@DataJpaTest` + Testcontainers + `@Sql` to verify state; confirm RED) at `services/authorization-service/src/test/java/com/creditcard/authorization/repository/ProcessedEventRepositoryTest.java`
- [X] T026 Implement `ProcessedEventRepository` with `@Modifying @Query("INSERT INTO processed_events (event_key, processed_at) VALUES (:eventKey, now()) ON CONFLICT DO NOTHING", nativeQuery=true) int insertIfAbsent(@Param("eventKey") String eventKey)` at `services/authorization-service/src/main/java/com/creditcard/authorization/repository/ProcessedEventRepository.java`; confirm GREEN

### Kafka Event POJOs

- [X] T027 [P] Write failing unit test for `TransactionInitiatedEvent` POJO (verify top-level fields: eventId, eventType, sourceService, traceId, occurredAt; verify nested `Payload` inner class fields: transactionId, cardToken, maskedCardNumber, cardholderName, amount, currencyCode; confirm RED) at `services/authorization-service/src/test/java/com/creditcard/authorization/event/TransactionInitiatedEventTest.java`
- [X] T028 Implement `TransactionInitiatedEvent` POJO with static nested `Payload` class; all fields with getters/setters; `@JsonIgnoreProperties(ignoreUnknown=true)` at `services/authorization-service/src/main/java/com/creditcard/authorization/event/TransactionInitiatedEvent.java`; confirm GREEN
- [X] T029 [P] Write failing unit test for `AuthorizationResultEvent` POJO (verify factory method `ofAuthorized(transactionId, traceId)` sets eventType="TransactionAuthorized"; `ofDeclined(...)` sets "TransactionDeclined"; `ofFailed(...)` sets "TransactionFailed"; sourceService="authorization-service"; confirm RED) at `services/authorization-service/src/test/java/com/creditcard/authorization/event/AuthorizationResultEventTest.java`
- [X] T030 Implement `AuthorizationResultEvent` POJO with nested `Payload` class (transactionId, outcome, reasonCode) and static factory methods `ofAuthorized`, `ofDeclined`, `ofFailed` at `services/authorization-service/src/main/java/com/creditcard/authorization/event/AuthorizationResultEvent.java`; confirm GREEN

### Idempotency Service

- [X] T031 Write failing unit test for `IdempotencyService` (verify `recordAndExecute(key, runnable)` executes runnable when `insertIfAbsent` returns 1; skips runnable when returns 0; propagates exception from runnable without calling `insertIfAbsent` again; confirm RED) at `services/authorization-service/src/test/java/com/creditcard/authorization/service/IdempotencyServiceTest.java`
- [X] T032 Implement `IdempotencyService` with `@Transactional void recordAndExecute(String eventKey, Runnable businessLogic)` delegating to `ProcessedEventRepository.insertIfAbsent` at `services/authorization-service/src/main/java/com/creditcard/authorization/service/IdempotencyService.java`; confirm GREEN

### Kafka Configuration

- [X] T033 [P] Write failing unit test for `KafkaConsumerConfig` (verify `kafkaListenerContainerFactory` bean is created; verify ack mode is MANUAL_IMMEDIATE; use reflection or SpringRunner to check property; confirm RED) at `services/authorization-service/src/test/java/com/creditcard/authorization/config/KafkaConsumerConfigTest.java`
- [X] T034 Implement `KafkaConsumerConfig` with `ConcurrentKafkaListenerContainerFactory<String, TransactionInitiatedEvent>` configured with `MANUAL_IMMEDIATE` AckMode, `JsonDeserializer` for `TransactionInitiatedEvent`, `trusted.packages=com.creditcard.authorization.event` at `services/authorization-service/src/main/java/com/creditcard/authorization/config/KafkaConsumerConfig.java`; confirm GREEN
- [X] T035 [P] Write failing unit test for `KafkaProducerConfig` (verify `kafkaTemplate()` bean returns non-null `KafkaTemplate`; confirm RED) at `services/authorization-service/src/test/java/com/creditcard/authorization/config/KafkaProducerConfigTest.java`
- [X] T036 Implement `KafkaProducerConfig` with `KafkaTemplate<String, AuthorizationResultEvent>` using `JsonSerializer`, `acks=all`, `enable.idempotence=true`, `retries=3` at `services/authorization-service/src/main/java/com/creditcard/authorization/config/KafkaProducerConfig.java`; confirm GREEN

### Logging Infrastructure

- [X] T037 [P] Write failing unit test for `PanMaskingFilter` (verify `decide()` returns DENY when format string contains a 13-digit sequence; verify DENY for 16-digit and 19-digit sequences; verify NEUTRAL for vault token format `vault:v1:...`; verify NEUTRAL for masked format `****-****-****-1234`; confirm RED) at `services/authorization-service/src/test/java/com/creditcard/authorization/config/logging/PanMaskingFilterTest.java`
- [X] T038 Implement `PanMaskingFilter` extending Logback `TurboFilter` with `Pattern.compile("\\b\\d{13,19}\\b")` matching on format string; return `FilterReply.DENY` on match, `FilterReply.NEUTRAL` otherwise at `services/authorization-service/src/main/java/com/creditcard/authorization/config/logging/PanMaskingFilter.java`; confirm GREEN
- [X] T039 Create `services/authorization-service/src/main/resources/logback-spring.xml` with: `<turboFilter class="...PanMaskingFilter"/>`, CONSOLE appender using `LogstashEncoder` with MDC fields (traceId, spanId), AUDIT_FILE rolling appender logging to `logs/audit.log`, root logger at INFO, Hibernate SQL + Kafka loggers at WARN

---

## Phase 3: User Story 1 — Card Validation and Credit Authorization (Priority: P1) 🎯 MVP

**Goal**: Consume `TransactionInitiated` events, validate card token against issuer registry,
check credit limit, persist `AuthorizationRecord`, publish `TransactionAuthorized` or
`TransactionDeclined` (or `TransactionFailed` on system error) to `transaction.status.updated`.

**Independent Test**: Publish a `TransactionInitiated` event for a seeded card with
sufficient credit; assert exactly one `TransactionAuthorized` event in `transaction.status.updated`,
one `authorization_records` row with `outcome=AUTHORIZED`, and `available_credit` reduced
by the transaction amount.

### Tests for User Story 1 ⚠️ Write FIRST — confirm RED before T046

- [X] T040 [P] [US1] Write failing unit tests for `AuthorizationService.authorize()` — AUTHORIZED path: active card + amount ≤ available credit → returns AUTHORIZED outcome, saves record, calls `cardAccountRepository.save()` with reduced credit at `services/authorization-service/src/test/java/com/creditcard/authorization/service/AuthorizationServiceTest.java`; confirm RED
- [X] T041 [P] [US1] Write failing unit tests for `AuthorizationService.authorize()` — INVALID_CARD path: `findByCardToken` returns empty → returns DECLINED(INVALID_CARD), saves record, does NOT modify available_credit; add to `AuthorizationServiceTest.java`; confirm RED
- [X] T042 [P] [US1] Write failing unit tests for `AuthorizationService.authorize()` — CARD_NOT_ACTIVE path: card found but status=BLOCKED → returns DECLINED(CARD_NOT_ACTIVE); EXPIRED card → same; add to `AuthorizationServiceTest.java`; confirm RED
- [X] T043 [P] [US1] Write failing unit tests for `AuthorizationService.authorize()` — INSUFFICIENT_CREDIT path: amount > available_credit → DECLINED(INSUFFICIENT_CREDIT); boundary: amount == available_credit → AUTHORIZED; add to `AuthorizationServiceTest.java`; confirm RED
- [X] T044 [P] [US1] Write failing unit tests for `AuthorizationService.authorize()` — FAILED path: `cardAccountRepository.findByCardToken` throws `RuntimeException` → returns FAILED(INTERNAL_ERROR), no credit deduction; add to `AuthorizationServiceTest.java`; confirm RED

### Implementation for User Story 1

- [X] T045 [US1] Implement `AuthorizationService.authorize(TransactionInitiatedEvent event)` with `@Transactional` and `@Retryable(retryFor={OptimisticLockingFailureException.class, CannotSerializeTransactionException.class}, maxAttempts=3, backoff=@Backoff(delay=50, multiplier=2, maxDelay=500))`: (1) findByCardToken → INVALID_CARD if null, (2) check status → CARD_NOT_ACTIVE if not ACTIVE, (3) parse amount, check ≤ availableCredit → INSUFFICIENT_CREDIT if not, (4) deduct credit + save account, (5) save AuthorizationRecord, (6) return outcome; wrap in try/catch RuntimeException → FAILED(INTERNAL_ERROR) + save record at `services/authorization-service/src/main/java/com/creditcard/authorization/service/AuthorizationService.java`; confirm GREEN on T040–T044
- [X] T046 [P] [US1] Write failing unit tests for `TransactionInitiatedListener` — success path: event arrives → idempotencyService.recordAndExecute called with correct key `auth:TransactionInitiated:{eventId}` → authorizationService.authorize called → kafkaTemplate.send called with TransactionAuthorized event → ack.acknowledge() called; confirm RED at `services/authorization-service/src/test/java/com/creditcard/authorization/api/kafka/TransactionInitiatedListenerTest.java`
- [X] T047 [P] [US1] Write failing unit tests for `TransactionInitiatedListener` — declined path: authorizationService returns DECLINED → kafkaTemplate.send called with TransactionDeclined + correct reasonCode → ack.acknowledge() called; add to `TransactionInitiatedListenerTest.java`; confirm RED
- [X] T048 [P] [US1] Write failing unit tests for `TransactionInitiatedListener` — failed path: authorizationService returns FAILED → TransactionFailed event published → ack.acknowledge() called; malformed amount in event → FAILED(INVALID_EVENT_PAYLOAD) published → ack called; add to `TransactionInitiatedListenerTest.java`; confirm RED
- [X] T049 [P] [US1] Write failing unit tests for `TransactionInitiatedListener` — no-ack path: idempotencyService.recordAndExecute throws RuntimeException → ack.acknowledge() is NEVER called; add to `TransactionInitiatedListenerTest.java`; confirm RED
- [X] T050 [US1] Implement `TransactionInitiatedListener` with `@KafkaListener(topics="transaction.initiated", containerFactory="kafkaListenerContainerFactory")`: extract idempotency key `"auth:" + event.getEventType() + ":" + event.getEventId()`; call `idempotencyService.recordAndExecute(key, () -> { outcome = authorizationService.authorize(event); })` inside try; build `AuthorizationResultEvent` from outcome; `kafkaTemplate.send("transaction.status.updated", transactionId, resultEvent)`; `ack.acknowledge()` after successful send; do NOT ack if idempotencyService throws at `services/authorization-service/src/main/java/com/creditcard/authorization/api/kafka/TransactionInitiatedListener.java`; confirm GREEN on T046–T049
- [X] T051 [US1] Write failing integration test `AuthorizationIntegrationTest` (authorized path): `@SpringBootTest @Testcontainers @EmbeddedKafka @ActiveProfiles("test")` — seed an ACTIVE card account with credit_limit=1000.00, available_credit=1000.00 via `@Sql`; publish `TransactionInitiated` event for 100.00 to `transaction.initiated`; await `transaction.status.updated` with Awaitility (max 10s); assert eventType="TransactionAuthorized"; assert `card_accounts.available_credit=900.00`; assert one `authorization_records` row with outcome=AUTHORIZED at `services/authorization-service/src/test/java/com/creditcard/authorization/integration/AuthorizationIntegrationTest.java`; confirm RED
- [X] T052 [US1] Write failing integration tests (declined paths) in `AuthorizationIntegrationTest`: (a) insufficient credit: seed card with available_credit=50.00, request 100.00 → TransactionDeclined + reasonCode=INSUFFICIENT_CREDIT + credit unchanged; (b) invalid card: unknown token → TransactionDeclined + reasonCode=INVALID_CARD; (c) blocked card: seed BLOCKED card → TransactionDeclined + reasonCode=CARD_NOT_ACTIVE; add to `AuthorizationIntegrationTest.java`; confirm RED then confirm GREEN after T050 is in place

**Checkpoint**: After T052 GREEN — US1 is fully functional. Publish a `TransactionInitiated`
event via `quickstart.md` Scenario 1 and verify end-to-end authorization.

---

## Phase 4: User Story 2 — Idempotent Duplicate Event Handling (Priority: P2)

**Goal**: Re-delivering the same `TransactionInitiated` event N times produces exactly one
`AuthorizationRecord`, exactly one result event in `transaction.status.updated`, and (if
AUTHORIZED) exactly one credit deduction.

**Independent Test**: Deliver the same event 10 times; assert one row in
`authorization_records`, one result event published, and `available_credit` reflects
exactly one deduction.

### Tests for User Story 2 ⚠️ Write FIRST — confirm RED before T055

- [X] T053 [US2] Write failing integration test `IdempotencyIntegrationTest`: `@SpringBootTest @Testcontainers @EmbeddedKafka` — seed ACTIVE card with available_credit=1000.00; publish the same `TransactionInitiated` event (same eventId) 10 times to `transaction.initiated`; await 10 acknowledgements; assert exactly ONE row in `authorization_records`; assert exactly ONE `TransactionAuthorized` event in `transaction.status.updated`; assert `available_credit=900.00` (not 100.00 or 0.00) at `services/authorization-service/src/test/java/com/creditcard/authorization/integration/IdempotencyIntegrationTest.java`; confirm RED
- [X] T054 [US2] Write failing integration test (concurrent delivery): publish same event from 5 concurrent threads; assert same single-outcome invariant; add to `IdempotencyIntegrationTest.java`; confirm RED
- [X] T055 [US2] Write failing integration test (terminal guard + idempotency together): authorize a transaction (AUTHORIZED), then re-deliver the same event 3 more times; assert credit deducted exactly once, result event published exactly once; add to `IdempotencyIntegrationTest.java`; confirm RED
- [X] T056 [US2] Confirm T053–T055 turn GREEN with the implementation already in place from Phase 3 (idempotency is already wired in `TransactionInitiatedListener` via `IdempotencyService`); if any test fails, diagnose and fix the gap in `IdempotencyService` or `TransactionInitiatedListener`

**Checkpoint**: After T056 GREEN — US2 proven correct. Repeat quickstart.md Scenario 4
(same event twice) and verify idempotency end-to-end.

---

## Phase 5: User Story 3 — Authorization Record Auditability (Priority: P3)

**Goal**: Every authorization decision (AUTHORIZED, DECLINED, FAILED) is durably persisted
with transaction ID, masked card token representation in logs, reason code, and timestamp.
Zero raw card numbers in any log output.

**Independent Test**: Process a mix of AUTHORIZED and DECLINED events and assert that each
`authorization_records` row contains all required fields; assert zero raw card numbers in
any captured log output.

### Tests for User Story 3 ⚠️ Write FIRST — confirm RED before T060

- [X] T057 [P] [US3] Write failing unit test verifying `AuthorizationRecord` always stores outcome and reasonCode together — AUTHORIZED record has reasonCode=null; DECLINED record has non-null reasonCode; FAILED record has non-null reasonCode; add to `AuthorizationRecordTest.java`; confirm RED
- [X] T058 [P] [US3] Write failing unit test for `AuthorizationService` verifying AUTHORIZED record contains: non-null transactionId UUID, non-null cardToken, non-null requestedAmount matching input, non-null currencyCode, outcome=AUTHORIZED, reasonCode=null, decidedAt not null; add to `AuthorizationServiceTest.java`; confirm RED
- [X] T059 [US3] Write failing integration test `AuditInvariantTest`: `@SpringBootTest @Testcontainers @EmbeddedKafka @ActiveProfiles("test")` — attach Logback `ListAppender` to root logger; run both an AUTHORIZED and a DECLINED authorization; collect all log messages; assert no log message contains a 13–19 digit numeric sequence (raw PAN); assert each `authorization_records` row has decidedAt, outcome, reasonCode set; assert card token in logs is either masked or the opaque vault format (not a numeric PAN) at `services/authorization-service/src/test/java/com/creditcard/authorization/integration/AuditInvariantTest.java`; confirm RED
- [X] T060 [US3] Confirm T057–T059 turn GREEN with existing implementation; if audit log test fails because `PanMaskingFilter` is not wired in test profile, add `logback-test.xml` that includes `PanMaskingFilter` and rerun

**Checkpoint**: After T060 GREEN — US3 proven. All three user stories independently
functional.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Contract tests for event schema, Dockerfile, docker-compose update,
Kubernetes manifests, and JaCoCo 100% coverage gate.

- [X] T061 [P] Write failing contract test `KafkaEventSchemaTest` (pure unit test, no Spring context): verify `AuthorizationResultEvent.ofAuthorized(txId, traceId).getEventType()` equals "TransactionAuthorized"; verify `ofDeclined(...)` eventType = "TransactionDeclined"; verify `ofFailed(...)` eventType = "TransactionFailed"; verify `sourceService = "authorization-service"`; verify `payload.transactionId` is not null; confirm RED at `services/authorization-service/src/test/java/com/creditcard/authorization/contract/KafkaEventSchemaTest.java`; confirm GREEN
- [X] T062 [P] Write failing contract test `TransactionInitiatedEventSchemaTest` (pure unit test): verify all 9 required fields are present on the POJO (eventId, eventType, sourceService, traceId, occurredAt, payload.transactionId, payload.cardToken, payload.amount, payload.currencyCode); deserialize a sample JSON string and assert all fields populated; confirm RED → GREEN at `services/authorization-service/src/test/java/com/creditcard/authorization/contract/TransactionInitiatedEventSchemaTest.java`
- [X] T063 Create `services/authorization-service/Dockerfile` with multi-stage build: `gradle:8-jdk17 AS builder` stage runs `./gradlew :services:authorization-service:bootJar --no-daemon`; `eclipse-temurin:17-jre-jammy` runtime stage with non-root `appuser`, `EXPOSE 8082`, `ENTRYPOINT ["java", "-jar", "app.jar"]`
- [X] T064 Update root `docker-compose.yml` to add `authorization-service` service: image built from `services/authorization-service/Dockerfile`; environment vars `SPRING_DATASOURCE_URL`, `SPRING_KAFKA_BOOTSTRAP_SERVERS`; `depends_on` cockroachdb (healthy) and kafka (healthy); ports `8082:8082`; same network as other services
- [X] T065 [P] Create `k8s/authorization-service/deployment.yaml`: namespace `credit-card`, 2 replicas, non-root security context (`runAsNonRoot: true`), all env vars from Secret `authorization-service-secrets`, resources requests 250m/512Mi limits 1000m/1Gi, liveness probe `/actuator/health/liveness` on port 8082, readiness probe `/actuator/health/readiness` on port 8082
- [X] T066 [P] Create `k8s/authorization-service/service.yaml`: ClusterIP Service, port 8082 (management), namespace `credit-card`
- [X] T067 Run `./gradlew :services:authorization-service:cleanTest :services:authorization-service:test jacocoTestReport jacocoTestCoverageVerification`; if coverage gate fails, identify missed instructions/branches from the JaCoCo XML report at `services/authorization-service/build/reports/jacoco/test/jacocoTestReport.xml`, write targeted tests to cover each gap, and rerun until `BUILD SUCCESSFUL`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately
- **Foundational (Phase 2)**: Depends on T007 (compileJava success) — BLOCKS all user stories
- **US1 (Phase 3)**: Depends on all Phase 2 tasks — TDD mandatory throughout
- **US2 (Phase 4)**: Depends on Phase 3 complete (idempotency is wired in US1 implementation)
- **US3 (Phase 5)**: Depends on Phase 3 + Phase 4 (auditability relies on AuthorizationRecord from US1)
- **Polish (Phase 6)**: Depends on all user stories passing (T067 is the final gate)

### User Story Dependencies

- **US1 (P1)**: Can start after Phase 2 — core authorization flow; no dependency on US2 or US3
- **US2 (P2)**: Inherits idempotency wired in US1; integration tests prove it correct
- **US3 (P3)**: Inherits `AuthorizationRecord` from US1; audit invariant tests verify no PAN leakage

### Parallel Opportunities

- T008, T010, T012 (enum tests) can run in parallel
- T014, T016 (entity tests) can run in parallel after T009, T011, T013
- T018, T019, T020 (migrations) can run in parallel
- T021, T023, T025 (repository tests) can run in parallel
- T027, T029 (event POJO tests) can run in parallel
- T033, T035 (Kafka config tests) can run in parallel
- T037 (PAN filter test) can run in parallel with T033/T035
- T040, T041, T042, T043, T044 (AuthorizationService test methods) can be written in parallel (all in one file)
- T046, T047, T048, T049 (Listener test methods) can be written in parallel (one file)
- T061, T062 (contract tests) can run in parallel
- T065, T066 (k8s manifests) can run in parallel

---

## Parallel Examples

### Phase 2 — Enum and Entity Tests in Parallel

```
Parallel:
  T008: CardAccountStatusTest.java
  T010: AuthorizationOutcomeTest.java
  T012: ReasonCodeTest.java

Then:
  T009 → T011 → T013 (implementations sequentially as each test confirms RED)

Parallel:
  T014: CardAccountTest.java
  T016: AuthorizationRecordTest.java
```

### Phase 3 — US1 Service Tests in Parallel

```
Parallel (all added to AuthorizationServiceTest.java):
  T040: AUTHORIZED path tests
  T041: INVALID_CARD path tests
  T042: CARD_NOT_ACTIVE path tests
  T043: INSUFFICIENT_CREDIT + boundary tests
  T044: FAILED path tests

Then:
  T045: Implement AuthorizationService (all 5 tests must turn GREEN together)

Parallel (all added to TransactionInitiatedListenerTest.java):
  T046: success path tests
  T047: declined path tests
  T048: failed/malformed path tests
  T049: no-ack path tests

Then:
  T050: Implement TransactionInitiatedListener
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001–T007)
2. Complete Phase 2: Foundational (T008–T039) — CRITICAL, blocks everything
3. Complete Phase 3: US1 — Card Validation and Credit Authorization (T040–T052)
4. **STOP and VALIDATE**: Run quickstart.md Scenarios 1–3; verify authorized, declined-insufficient, declined-invalid-card
5. If valid, proceed to US2 and US3

### Incremental Delivery

1. Setup + Foundational → service compiles, DB schema migrated
2. US1 → full authorization pipeline working (authorized + declined + failed)
3. US2 → idempotency proven under duplicate delivery
4. US3 → audit invariant proven (no raw PANs in logs or records)
5. Polish → Dockerfile, k8s, JaCoCo gate green

---

## Notes

- TDD is mandatory per constitution Principle II: every class must have a failing test
  before implementation begins
- `@Retryable` on `AuthorizationService.authorize()` handles both `OptimisticLockingFailureException`
  (JPA version conflict) and `CannotSerializeTransactionException` (CockroachDB SQLSTATE 40001)
- Card tokens in `authorization_records` are stored as opaque Vault ciphertext strings —
  never decrypted by this service
- The `PanMaskingFilter` is a defense-in-depth safeguard — the authorization-service
  should never have a raw PAN in any variable or log message anyway
- Kafka offset is acknowledged ONLY after the result event is published — if Kafka publish
  fails, the consumer retries (at-least-once delivery with exactly-once business effect
  via idempotency)
- `available_credit` MUST never go below 0; the `CHECK (available_credit >= 0)` DB
  constraint is the last line of defense; service logic must prevent this before the DB
  write
