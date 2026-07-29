---
description: "Task list for transaction-service implementation"
---

# Tasks: Transaction Service

**Input**: Design documents from `/specs/001-transaction-service/`

**Prerequisites**: plan.md ✅ | spec.md ✅ | research.md ✅ | data-model.md ✅ | contracts/ ✅

**TDD Mandate**: Tests are MANDATORY per constitution Principle II and spec SC-004/SC-005.
Every class requires: (1) write test → (2) confirm RED → (3) implement → (4) confirm GREEN.
No implementation task may begin until its test task is complete and confirmed failing.

**Organization**: Tasks organized by user story. Each story is independently testable.

## Format: `[ID] [P?] [Story?] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (US1, US2, US3, US4)
- Each task includes exact file path

---

## Phase 1: Setup

**Purpose**: Initialize the Gradle multi-project build, create project skeleton, and copy
the approved proto contract to its authoritative location.

- [x] T001 Initialize root Gradle multi-project build: create `settings.gradle` (include `:services:transaction-service`) and root `build.gradle` (shared plugin versions + subproject conventions) at repository root
- [x] T002 Create `services/transaction-service/build.gradle` with all dependencies: Spring Boot 3.x, Spring Data JPA, Spring Kafka, Spring Vault (`spring-vault-core`), LogNet gRPC starter (`net.devh:grpc-spring-boot-starter`), grpc-protobuf, grpc-stub, protobuf-java, postgresql driver, Micrometer Tracing, Testcontainers (postgresql + kafka), JUnit 5, Mockito; configure `com.google.protobuf:0.9.4` plugin for stub generation; configure JaCoCo 100% instruction and branch coverage gate
- [x] T003 [P] Create authoritative `proto/v1/transaction_service.proto` at repository root (copy from approved spec at `specs/001-transaction-service/contracts/transaction_service.proto`); create `proto/v1/` directory
- [x] T004 Verify Gradle protobuf stub generation succeeds with no errors: `./gradlew :services:transaction-service:generateProto`; confirm stubs land in `build/generated/source/proto/main/`
- [x] T005 Create `TransactionServiceApplication.java` Spring Boot entry point at `services/transaction-service/src/main/java/com/creditcard/transaction/TransactionServiceApplication.java`
- [x] T006 [P] Create `application.yml` (base config: gRPC port 9090, management port 8081, Kafka bootstrap, Vault host, CockroachDB JDBC URL placeholder) and `application-local.yml` (Docker Compose local values) at `services/transaction-service/src/main/resources/`
- [x] T007 [P] Create `application-test.yml` (Testcontainers overrides, disable Vault in unit tests) at `services/transaction-service/src/test/resources/`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Core domain entities, repositories, idempotency service, and all config beans
that every user story depends on. No user story work begins until this phase is complete.

**⚠️ CRITICAL**: User stories cannot start until all Phase 2 tasks are approved and green.

### Domain Entities

- [x] T008 [P] Write failing unit test for `TransactionStatus` enum (verify all four values: PENDING, AUTHORIZED, DECLINED, FAILED; verify terminal states; confirm RED) at `services/transaction-service/src/test/java/com/creditcard/transaction/domain/TransactionStatusTest.java`
- [x] T009 Implement `TransactionStatus` enum with `isTerminal()` helper at `services/transaction-service/src/main/java/com/creditcard/transaction/domain/TransactionStatus.java`
- [x] T010 [P] Write failing unit test for `Transaction` JPA entity (verify all fields, state-transition guard rejects overwriting terminal status, `@Version` present; confirm RED) at `services/transaction-service/src/test/java/com/creditcard/transaction/domain/TransactionTest.java`
- [x] T011 Implement `Transaction` JPA entity (`@Entity`, `@Table("transactions")`, fields: id UUID, cardToken, maskedCardNumber, cardholderName, amount, currencyCode, status, createdAt, updatedAt, version; `@UuidGenerator` on id; `@Version` on version) at `services/transaction-service/src/main/java/com/creditcard/transaction/domain/Transaction.java`
- [x] T012 [P] Write failing unit test for `ProcessedEvent` JPA entity (verify eventKey PK, processedAt field; confirm RED) at `services/transaction-service/src/test/java/com/creditcard/transaction/domain/ProcessedEventTest.java`
- [x] T013 Implement `ProcessedEvent` JPA entity (`@Entity`, `@Table("processed_events")`, eventKey VARCHAR(255) PK, processedAt Instant) at `services/transaction-service/src/main/java/com/creditcard/transaction/domain/ProcessedEvent.java`

### Database Migration

- [x] T014 Create Flyway migration `V1__init.sql` (creates `transactions` and `processed_events` tables per `data-model.md` DDL; indexes on `transactions.status`) at `services/transaction-service/src/main/resources/db/migration/V1__init.sql`

### Repositories

- [x] T015 [P] Write failing integration test for `TransactionRepository` (save, findById, update status via `@Modifying` query; uses Testcontainers CockroachDB; confirm RED) at `services/transaction-service/src/test/java/com/creditcard/transaction/repository/TransactionRepositoryTest.java`
- [x] T016 Implement `TransactionRepository` (extends `JpaRepository<Transaction, UUID>`; add `@Modifying @Query` method to update status by id) at `services/transaction-service/src/main/java/com/creditcard/transaction/repository/TransactionRepository.java`
- [x] T017 [P] Write failing integration test for `ProcessedEventRepository` (save, existsByEventKey; uses Testcontainers CockroachDB; confirm RED) at `services/transaction-service/src/test/java/com/creditcard/transaction/repository/ProcessedEventRepositoryTest.java`
- [x] T018 Implement `ProcessedEventRepository` (extends `JpaRepository<ProcessedEvent, String>`; add `existsByEventKey(String)` method) at `services/transaction-service/src/main/java/com/creditcard/transaction/repository/ProcessedEventRepository.java`

### Idempotency Service

- [x] T019 [P] Write failing unit test for `IdempotencyService` (test: event not seen → processes and records; event already seen → skips; atomic commit verified via mocked repo; confirm RED) at `services/transaction-service/src/test/java/com/creditcard/transaction/service/IdempotencyServiceTest.java`
- [x] T020 Implement `IdempotencyService` (`isAlreadyProcessed(eventKey)` checks `ProcessedEventRepository`; `recordAndExecute(eventKey, Runnable)` inserts `ProcessedEvent` and runs business logic in one `@Transactional`; handles duplicate key gracefully with no exception) at `services/transaction-service/src/main/java/com/creditcard/transaction/service/IdempotencyService.java`

### Event POJOs

- [x] T021 [P] Create `TransactionInitiatedEvent` POJO (fields: eventId, eventType, sourceService, traceId, occurredAt, payload with transactionId/cardToken/maskedCardNumber/cardholderName/amount/currencyCode) at `services/transaction-service/src/main/java/com/creditcard/transaction/event/TransactionInitiatedEvent.java`
- [x] T022 [P] Create `StatusUpdateEvent` POJO (fields: eventId, eventType, sourceService, traceId, occurredAt, payload with transactionId plus event-type-specific fields) at `services/transaction-service/src/main/java/com/creditcard/transaction/event/StatusUpdateEvent.java`

### Configuration

- [x] T023 [P] Implement `KafkaConfig` (producer factory for `transaction.initiated` topic: `JsonSerializer`, `acks=all`, `enable.idempotence=true`, key=transactionId; consumer factory for `transaction.status.updated`: `ErrorHandlingDeserializer` wrapping `JsonDeserializer`, group-id `transaction-service-status-consumer`, `AckMode.MANUAL_IMMEDIATE`, `auto.offset.reset=earliest`) at `services/transaction-service/src/main/java/com/creditcard/transaction/config/KafkaConfig.java`
- [x] T024 [P] Implement `VaultConfig` (configure `VaultTemplate` with host/port/scheme from properties; AppRole auth for K8s, token-based for local; transit key name from `spring.vault.transit.key-name` property) at `services/transaction-service/src/main/java/com/creditcard/transaction/config/VaultConfig.java`
- [x] T025 [P] Implement `ObservabilityConfig` (configure Micrometer Tracing with Brave bridge; trace ID propagation in gRPC metadata headers and Kafka message headers; structured JSON logging) at `services/transaction-service/src/main/java/com/creditcard/transaction/config/ObservabilityConfig.java`

### Context Load Verification

- [x] T026 Write `@SpringBootTest` context load test (verifies all beans wire up: TransactionRepository, ProcessedEventRepository, IdempotencyService, KafkaConfig, VaultConfig present; uses `@ActiveProfiles("test")` with mocked external dependencies) at `services/transaction-service/src/test/java/com/creditcard/transaction/TransactionServiceApplicationTest.java`

**Checkpoint**: Foundation complete — all domain, repository, idempotency, and config
tasks are green. User story implementation can now begin.

---

## Phase 3: User Story 1 — Card Payment Submission (Priority: P1) 🎯 MVP

**Goal**: Accept a payment request, tokenize the card number via Vault, persist as PENDING,
publish `TransactionInitiated` event, return transactionId. This is the entire payment
creation flow.

**Independent Test**: Submit a well-formed gRPC `InitiateTransaction` request → verify
transactionId returned, DB record shows card token (NOT raw PAN) with PENDING status,
Kafka `transaction.initiated` topic received the event.

### Tests for User Story 1 — Write First, Confirm RED

- [x] T027 [P] [US1] Write failing unit test for `TokenizationService` (mock `VaultOperations`; verify: encrypt called with raw PAN, token returned, masked number extracted as last 4 digits, raw PAN not stored on returned object; vault-unavailable path throws exception; confirm RED) at `services/transaction-service/src/test/java/com/creditcard/transaction/service/TokenizationServiceTest.java`
- [x] T028 [P] [US1] Write failing unit test for `TransactionService.initiateTransaction()` (mock `TokenizationService`, `TransactionRepository`, `KafkaTemplate`; verify: tokenize called with raw PAN, PENDING transaction saved, `TransactionInitiatedEvent` published to `transaction.initiated`, transactionId returned, CVV/expiry NOT on saved entity; confirm RED) at `services/transaction-service/src/test/java/com/creditcard/transaction/service/TransactionServiceTest.java`
- [x] T029 [P] [US1] Write failing unit test for `TransactionGrpcService.initiateTransaction()` (mock `TransactionService`; verify: input validation rejects missing fields, valid request calls `transactionService.initiateTransaction()`, returns `InitiateTransactionResponse` with transactionId and "PENDING" status; confirm RED) at `services/transaction-service/src/test/java/com/creditcard/transaction/api/grpc/TransactionGrpcServiceTest.java`

### Implementation for User Story 1

- [x] T030 [US1] Implement `TokenizationService` (`tokenize(String rawPan)` calls `vaultOperations.opsForTransit().encrypt(keyName, rawPan)`, returns `TokenResult` with ciphertext token and masked number `****-****-****-XXXX` extracted from last 4 digits; raw PAN reference dropped immediately) at `services/transaction-service/src/main/java/com/creditcard/transaction/service/TokenizationService.java`
- [x] T031 [US1] Implement `TransactionService.initiateTransaction()` (`@Transactional`: call `TokenizationService.tokenize()`, build and save `Transaction` entity with cardToken + maskedCardNumber + PENDING status, publish `TransactionInitiatedEvent` to Kafka, return transactionId; if Vault throws, propagate exception — no partial record created) at `services/transaction-service/src/main/java/com/creditcard/transaction/service/TransactionService.java`
- [x] T032 [US1] Implement `TransactionGrpcService` gRPC service class (`@GrpcService`; `initiateTransaction()` validates input fields, calls `TransactionService.initiateTransaction()`, returns `InitiateTransactionResponse`; handle exceptions as gRPC `Status` codes) at `services/transaction-service/src/main/java/com/creditcard/transaction/api/grpc/TransactionGrpcService.java`

### Integration Test for User Story 1

- [x] T033 [US1] Write and verify `TransactionCreationIntegrationTest` (`@SpringBootTest` + Testcontainers CockroachDB + Kafka + mocked Vault; full flow: call `TransactionGrpcService.initiateTransaction()` → assert transactionId returned → assert DB row has `card_token` starting with `vault:v1:`, `masked_card_number` = `****-****-****-XXXX`, `status` = PENDING → assert `transaction.initiated` Kafka topic received `TransactionInitiatedEvent` with no raw PAN; also test: Vault unavailable → exception, no DB row created) at `services/transaction-service/src/test/java/com/creditcard/transaction/integration/TransactionCreationIntegrationTest.java`

**Checkpoint**: User Story 1 is fully functional and independently testable. Tokenization
verified. Raw card number never in DB or Kafka event. MVP deliverable.

---

## Phase 4: User Story 2 — Transaction Authorized (Priority: P2)

**Goal**: When an AUTHORIZED or DECLINED Kafka event arrives from `authorization-service`,
update the transaction status accordingly. Terminal states are protected from overwriting.

**Independent Test**: Create a PENDING transaction → publish `TransactionAuthorized` event
to `transaction.status.updated` → verify transaction status becomes AUTHORIZED. Repeat for
DECLINED. Verify a second event for the same transaction leaves status unchanged.

### Tests for User Story 2 — Write First, Confirm RED

- [x] T034 [P] [US2] Write failing unit tests for `TransactionService.handleAuthorized()` and `handleDeclined()` (mock `TransactionRepository`; verify: PENDING → AUTHORIZED transition saved; PENDING → DECLINED transition saved; AUTHORIZED transaction ignores handleAuthorized call (terminal guard); confirm RED) at `services/transaction-service/src/test/java/com/creditcard/transaction/service/TransactionServiceTest.java`
- [x] T035 [P] [US2] Write failing unit test for `TransactionStatusListener` AUTHORIZED and DECLINED handlers (mock `TransactionService` + `IdempotencyService`; verify: `IdempotencyService.recordAndExecute()` called with correct eventKey; `transactionService.handleAuthorized()`/`handleDeclined()` invoked; Kafka message acknowledged after processing; confirm RED) at `services/transaction-service/src/test/java/com/creditcard/transaction/api/kafka/TransactionStatusListenerTest.java`

### Implementation for User Story 2

- [x] T036 [US2] Implement `TransactionService.handleAuthorized(String transactionId)` and `handleDeclined(String transactionId)` (`@Transactional`; load transaction, check `status.isTerminal()` — if terminal, return without update; else update status to AUTHORIZED/DECLINED) at `services/transaction-service/src/main/java/com/creditcard/transaction/service/TransactionService.java`
- [x] T037 [US2] Implement `TransactionStatusListener` (`@KafkaListener(topics = "transaction.status.updated")`; deserialize `StatusUpdateEvent`; route by `eventType` to correct handler; wrap in `IdempotencyService.recordAndExecute()` with eventKey `"tx:" + eventType + ":" + eventId`; manually acknowledge Kafka offset after successful processing) at `services/transaction-service/src/main/java/com/creditcard/transaction/api/kafka/TransactionStatusListener.java`

### Integration Test for User Story 2

- [x] T038 [US2] Write and verify `StatusUpdateIntegrationTest` — AUTHORIZED and DECLINED cases (create PENDING transaction → publish `TransactionAuthorized` event → assert status is AUTHORIZED; create PENDING transaction → publish `TransactionDeclined` event → assert status is DECLINED; publish second AUTHORIZED event for same transaction → assert status still AUTHORIZED, no error) at `services/transaction-service/src/test/java/com/creditcard/transaction/integration/StatusUpdateIntegrationTest.java`

**Checkpoint**: User Story 2 complete. AUTHORIZED and DECLINED flows verified independently.

---

## Phase 5: User Story 3 — Transaction Failed (Priority: P3)

**Goal**: When a FAILED Kafka event arrives (system error from downstream), update
transaction status to FAILED. Terminal states (AUTHORIZED, DECLINED) are protected.

**Independent Test**: Create a PENDING transaction → publish `TransactionFailed` event →
verify status is FAILED. Verify an already-AUTHORIZED transaction ignores a FAILED event.

### Tests for User Story 3 — Write First, Confirm RED

- [x] T039 [P] [US3] Write failing unit test for `TransactionService.handleFailed()` (verify PENDING → FAILED transition; verify AUTHORIZED transaction is not overwritten by FAILED; confirm RED) at `services/transaction-service/src/test/java/com/creditcard/transaction/service/TransactionServiceTest.java`
- [x] T040 [P] [US3] Write failing unit test for `TransactionStatusListener` FAILED handler (verify `IdempotencyService.recordAndExecute()` called; `transactionService.handleFailed()` invoked; Kafka offset acknowledged; confirm RED) at `services/transaction-service/src/test/java/com/creditcard/transaction/api/kafka/TransactionStatusListenerTest.java`

### Implementation for User Story 3

- [x] T041 [US3] Implement `TransactionService.handleFailed(String transactionId)` (`@Transactional`; load transaction, check `status.isTerminal()` — if terminal, return without update; else update status to FAILED) at `services/transaction-service/src/main/java/com/creditcard/transaction/service/TransactionService.java`
- [x] T042 [US3] Add `TransactionFailed` event routing to `TransactionStatusListener` (handle `"TransactionFailed"` eventType → call `transactionService.handleFailed()` within idempotency wrapper) at `services/transaction-service/src/main/java/com/creditcard/transaction/api/kafka/TransactionStatusListener.java`

### Integration Test for User Story 3

- [x] T043 [US3] Add FAILED test case to `StatusUpdateIntegrationTest` (create PENDING transaction → publish `TransactionFailed` → assert FAILED status; create AUTHORIZED transaction → publish `TransactionFailed` → assert still AUTHORIZED) at `services/transaction-service/src/test/java/com/creditcard/transaction/integration/StatusUpdateIntegrationTest.java`

**Checkpoint**: User Story 3 complete. All three terminal states (AUTHORIZED, DECLINED,
FAILED) independently functional.

---

## Phase 6: User Story 4 — Idempotent Duplicate Notification Handling (Priority: P2)

**Goal**: Delivering the same Kafka event N times produces exactly one state change.
The `IdempotencyService` (built in Phase 2) is validated under duplicate-delivery conditions.

**Independent Test**: Deliver same `TransactionAuthorized` event 10 times → exactly one
AUTHORIZED state change, remaining 9 deliveries acknowledged silently with no errors or
duplicate DB entries.

### Tests for User Story 4 — Write First, Confirm RED

- [x] T044 [P] [US4] Write failing integration test: same `TransactionAuthorized` event delivered twice → exactly one state change, `processed_events` has exactly one row for the eventKey, no exception on second delivery; confirm RED at `services/transaction-service/src/test/java/com/creditcard/transaction/integration/IdempotencyIntegrationTest.java`
- [x] T045 [P] [US4] Write failing integration test: same `TransactionFailed` event delivered 10 times → exactly one FAILED state, exactly one `processed_events` row; confirm RED at `services/transaction-service/src/test/java/com/creditcard/transaction/integration/IdempotencyIntegrationTest.java`
- [x] T046 [P] [US4] Write failing integration test: AUTHORIZED transaction receives second `TransactionAuthorized` event → status remains AUTHORIZED, no error, terminal state guard exercised alongside idempotency; confirm RED at `services/transaction-service/src/test/java/com/creditcard/transaction/integration/IdempotencyIntegrationTest.java`

### Implementation Verification for User Story 4

- [x] T047 [US4] Verify `IdempotencyService.recordAndExecute()` handles duplicate `processed_events` INSERT without throwing (use `INSERT ... ON CONFLICT DO NOTHING` in native query or catch `DataIntegrityViolationException` and treat as already-processed); update `IdempotencyService` if needed at `services/transaction-service/src/main/java/com/creditcard/transaction/service/IdempotencyService.java`
- [x] T048 [US4] Verify `TransactionService` terminal-state guard is exercised by idempotency integration tests — no code change needed if guard already rejects overwrites; confirm `TransactionService` terminal guard + `IdempotencyService` together cover all duplicate scenarios at `services/transaction-service/src/main/java/com/creditcard/transaction/service/TransactionService.java`

**Checkpoint**: All four user stories independently functional and idempotency verified
under duplicate-delivery conditions. Full business logic complete.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Complete the gRPC `GetTransaction` RPC, contract tests, audit logging,
security invariant validation, and deployment artifacts.

- [ ] T049 [P] Write failing unit test for `TransactionGrpcService.getTransaction()` (verify: valid transactionId returns `GetTransactionResponse` with maskedCardNumber and status but NOT card token; unknown transactionId returns gRPC `NOT_FOUND` status; confirm RED) at `services/transaction-service/src/test/java/com/creditcard/transaction/api/grpc/TransactionGrpcServiceTest.java`
- [ ] T050 Implement `TransactionGrpcService.getTransaction()` (load transaction by id, map to `GetTransactionResponse` — include maskedCardNumber, status, amounts; explicitly exclude cardToken from response) at `services/transaction-service/src/main/java/com/creditcard/transaction/api/grpc/TransactionGrpcService.java`
- [ ] T051 Write and verify `TransactionGrpcContractTest` (validates both RPCs — `InitiateTransaction` and `GetTransaction` — against generated proto stubs; verifies request/response field names and types match proto definition) at `services/transaction-service/src/test/java/com/creditcard/transaction/contract/TransactionGrpcContractTest.java`
- [ ] T052 [P] Create `logback-spring.xml` with: (1) structured JSON console appender (`logstash-logback-encoder`) for all application logs including traceId/spanId; (2) dedicated `audit-log` file appender for financial state transitions (PENDING/AUTHORIZED/DECLINED/FAILED); (3) WARN-level filter to block raw card number patterns at `services/transaction-service/src/main/resources/logback-spring.xml`
- [ ] T053 [P] Add log-scanning security assertion test: run full `TransactionCreationIntegrationTest`, capture log output, assert no 13-19 digit numeric sequences matching PAN pattern appear anywhere at `services/transaction-service/src/test/java/com/creditcard/transaction/integration/SecurityInvariantTest.java`
- [ ] T054 [P] Create multi-stage `Dockerfile` (Stage 1: `gradle:8-jdk17` builder runs `./gradlew :services:transaction-service:bootJar`; Stage 2: `eclipse-temurin:17-jre-jammy` copies jar, sets non-root user, EXPOSE 9090 8081, ENTRYPOINT java -jar) at `services/transaction-service/Dockerfile`
- [ ] T055 [P] Add `transaction-service` block to `docker-compose.yml` at repository root (image build from `services/transaction-service/Dockerfile`; env vars: VAULT_ADDR, VAULT_TOKEN, SPRING_DATASOURCE_URL, SPRING_KAFKA_BOOTSTRAP_SERVERS; ports: 9090, 8081; depends_on: cockroachdb, kafka, vault)
- [ ] T056 [P] Create `k8s/transaction-service/deployment.yaml` (Deployment: image from GitLab registry, 2 replicas, env vars from Kubernetes Secrets, resource limits, liveness/readiness probes on `/actuator/health`)
- [ ] T057 [P] Create `k8s/transaction-service/service.yaml` (ClusterIP Service: port 9090 gRPC, named `grpc`; port 8081 HTTP for management)
- [ ] T058 Run end-to-end quickstart.md validation (docker-compose up → vault-init.sh → grpcurl `InitiateTransaction` → verify DB shows token not PAN → grpcurl `GetTransaction` → verify masked number returned → docker-compose down)
- [x] T059 Run full test suite and confirm JaCoCo 100% coverage gate passes: `./gradlew :services:transaction-service:test jacocoTestReport jacocoTestCoverageVerification`; if gate fails, write missing tests before proceeding

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)**: No dependencies — start immediately
- **Phase 2 (Foundational)**: Depends on Phase 1 completion — **BLOCKS all user stories**
- **User Stories (Phases 3–6)**: All depend on Phase 2 completion
  - US1 (Phase 3) must complete before US2, US3, US4 (repositories needed in integration tests)
  - US2 (Phase 4) and US3 (Phase 5) can run in parallel after US1 completes
  - US4 (Phase 6) requires US2 and US3 consumers to be implemented first
- **Polish (Phase 7)**: Depends on all user story phases being complete

### User Story Dependencies

| Story | Depends On | Can Start After |
|-------|-----------|----------------|
| US1 (P1) | Phase 2 complete | Foundational checkpoint |
| US2 (P2) | Phase 3 complete | US1 checkpoint |
| US3 (P3) | Phase 3 complete | US1 checkpoint (parallel with US2) |
| US4 (P2) | Phases 4 + 5 complete | US2 and US3 both green |

### Within Each User Story

1. All test tasks (TNN [P] [USX]) — write first, confirm FAILING
2. Implementation tasks (non-[P]) — implement one at a time, confirm GREEN per test
3. Integration test — write and verify against running Testcontainers
4. Checkpoint approved before next story begins (Principle IV)

### Parallel Opportunities

Tasks marked **[P]** can run in parallel when team has capacity:

```bash
# Phase 2 — write all domain tests in parallel:
T008 TransactionStatusTest.java
T010 TransactionTest.java
T012 ProcessedEventTest.java

# Phase 2 — write repository tests in parallel (after domain entities done):
T015 TransactionRepositoryTest.java
T017 ProcessedEventRepositoryTest.java

# Phase 2 — config classes in parallel (no inter-dependencies):
T023 KafkaConfig.java
T024 VaultConfig.java
T025 ObservabilityConfig.java

# Phase 3 — write US1 tests in parallel:
T027 TokenizationServiceTest.java
T028 TransactionServiceTest.java (initiateTransaction)
T029 TransactionGrpcServiceTest.java (initiateTransaction)

# Phase 4+5 — US2 and US3 can overlap (different handlers):
T034 TransactionServiceTest.java (handleAuthorized/handleDeclined)
T039 TransactionServiceTest.java (handleFailed)
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001–T007)
2. Complete Phase 2: Foundational (T008–T026) — **GATE: nothing else starts until here**
3. Complete Phase 3: User Story 1 (T027–T033)
4. **STOP AND VALIDATE**: Run `TransactionCreationIntegrationTest`, inspect DB for token-not-PAN, check Kafka event
5. Deploy/demo MVP: payment creation works, card data is tokenized

### Incremental Delivery

1. Phase 1 + 2 → Foundation ready
2. Phase 3 (US1) → Payment creation MVP — demo-ready
3. Phase 4 (US2) → AUTHORIZED / DECLINED flows — authorization observable
4. Phase 5 (US3) → FAILED flow — all three terminal states covered
5. Phase 6 (US4) → Idempotency validated — production-safe under retries
6. Phase 7 → Deployment artifacts + security audit — ready for service integration

### Each Story Independently Testable

| Story | Test Command |
|-------|-------------|
| US1 | `./gradlew :services:transaction-service:test --tests "*.TransactionCreationIntegrationTest"` |
| US2 | `./gradlew :services:transaction-service:test --tests "*.StatusUpdateIntegrationTest"` |
| US3 | `./gradlew :services:transaction-service:test --tests "*.StatusUpdateIntegrationTest"` |
| US4 | `./gradlew :services:transaction-service:test --tests "*.IdempotencyIntegrationTest"` |
| All | `./gradlew :services:transaction-service:test` |

---

## Notes

- [P] tasks = different files, no blocking dependencies — parallelizable with multiple developers
- Principle II: tests MUST be written and CONFIRMED FAILING before any implementation task begins
- Principle IV: one class at a time — complete and approve each test-implement pair before the next
- Principle I: if any test reveals a raw card number in logs, DB, or events → immediate stop and fix before continuing
- Tasks T047 and T048 may require no code change if Phases 2–5 already satisfy the idempotency contract — verify rather than implement blindly
- Do not add T056–T057 K8s manifests to GitLab CI/CD until the `api-gateway` service is also specced (they share the same cluster)
