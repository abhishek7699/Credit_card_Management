# Tasks: Notification Service

**Input**: Design documents from `/specs/004-notification-service/`

**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, contracts/ ✅, quickstart.md ✅

**Tests**: TDD with 100% instruction + branch coverage is mandatory per project constitution. Test tasks are included and MUST be written before implementation within each phase.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)
- Exact file paths included in all descriptions

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Initialize the `services/notification-service/` Gradle project with all dependencies and base configuration.

- [X] T001 Create Gradle project structure: `services/notification-service/` directory with `src/main/java/com/creditcard/notification/`, `src/main/resources/db/migration/`, `src/test/java/com/creditcard/notification/`
- [X] T002 Create `services/notification-service/build.gradle` with Spring Boot 3.2.5, `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-kafka`, `flyway-core`, `postgresql`, `lombok`, `spring-boot-starter-validation`; test deps: `spring-kafka-test`, `testcontainers:postgresql`; JaCoCo 0.8.11 with 100% instruction + branch coverage gate (same config as fraud-detection-service)
- [X] T003 [P] Create `services/notification-service/src/main/java/com/creditcard/notification/NotificationServiceApplication.java` — `@SpringBootApplication` main class
- [X] T004 [P] Create `services/notification-service/src/main/resources/application.yml` (server.port=8080, management.server.port=8084, spring.kafka.consumer.group-id=notification-service-consumer, spring.kafka.consumer.auto-offset-reset=earliest, spring.jpa.hibernate.ddl-auto=validate, spring.flyway.enabled=true) and `application-test.yml` (EmbeddedKafka bootstrap-servers placeholder, Testcontainers datasource override)
- [X] T005 [P] Create `services/notification-service/Dockerfile` — multi-stage build: `gradle:8-jdk17` builder stage then `eclipse-temurin:17-jre-jammy` runtime stage (same pattern as fraud-detection-service)

**Checkpoint**: Project structure and configuration in place — Gradle build resolves dependencies

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Flyway migrations, JPA entities, repositories, channel abstraction, Kafka config, and PAN masking filter — all must be complete before any user story consumer or controller can be built.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete

### Flyway Migrations

- [X] T006 Create `services/notification-service/src/main/resources/db/migration/V1__create_cardholder_contacts.sql` — `cardholder_contacts` table: `card_token VARCHAR(512) PRIMARY KEY`, `masked_card_number VARCHAR(19) NOT NULL`, `email VARCHAR(320)`, `phone_number VARCHAR(20)`, `transaction_notifications_enabled BOOLEAN NOT NULL DEFAULT TRUE`, `registered_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
- [X] T007 [P] Create `services/notification-service/src/main/resources/db/migration/V2__create_notification_records.sql` — `notification_records` table: `id UUID PRIMARY KEY`, `event_id VARCHAR(100) NOT NULL`, `event_type VARCHAR(50) NOT NULL`, `channel VARCHAR(10) NOT NULL`, `recipient_masked VARCHAR(100) NOT NULL`, `content TEXT NOT NULL`, `status VARCHAR(10) NOT NULL DEFAULT 'PENDING'`, `failure_reason TEXT`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `sent_at TIMESTAMPTZ`; indexes on `(event_id)` and `(status)`
- [X] T008 [P] Create `services/notification-service/src/main/resources/db/migration/V3__create_processed_events.sql` — `processed_events` table: `event_key VARCHAR(255) PRIMARY KEY`, `processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`

### JPA Entities

- [X] T009 Create `services/notification-service/src/main/java/com/creditcard/notification/domain/CardholderContact.java` — `@Entity @Table(name="cardholder_contacts")`, `@Id String cardToken`, `maskedCardNumber`, `email`, `phoneNumber`, `transactionNotificationsEnabled` (default true), `registeredAt`, `updatedAt`; `@PreUpdate` sets `updatedAt`
- [X] T010 [P] Create `services/notification-service/src/main/java/com/creditcard/notification/domain/NotificationRecord.java` — `@Entity @Table(name="notification_records")`, `@Id @GeneratedValue UUID id`, `eventId`, `eventType`, `channel` (VARCHAR 10), `recipientMasked`, `content`, `status` (default PENDING), `failureReason`, `createdAt`, `sentAt`
- [X] T011 [P] Create `services/notification-service/src/main/java/com/creditcard/notification/domain/ProcessedEvent.java` — `@Entity @Table(name="processed_events")`, `@Id String eventKey`, `processedAt`; no-arg constructor + all-args constructor

### Repositories

- [X] T012 Create `services/notification-service/src/main/java/com/creditcard/notification/repository/CardholderContactRepository.java` — `JpaRepository<CardholderContact, String>`
- [X] T013 [P] Create `services/notification-service/src/main/java/com/creditcard/notification/repository/NotificationRecordRepository.java` — `JpaRepository<NotificationRecord, UUID>`
- [X] T014 [P] Create `services/notification-service/src/main/java/com/creditcard/notification/repository/ProcessedEventRepository.java` — `JpaRepository<ProcessedEvent, String>`; add `@Modifying @Query("INSERT INTO processed_events(event_key, processed_at) VALUES (:key, NOW()) ON CONFLICT DO NOTHING") int insertIfAbsent(@Param("key") String key)` native query

### Security & Logging

- [X] T015 Create `services/notification-service/src/main/java/com/creditcard/notification/config/logging/PanMaskingFilter.java` — Logback `TurboFilter`; regex `\b\d{13,19}\b` → `[MASKED]` applied to formatted log message; `NEUTRAL` on null format (same pattern as fraud-detection-service)
- [X] T016 [P] Write `services/notification-service/src/test/java/com/creditcard/notification/config/logging/PanMaskingFilterTest.java` — unit tests for: PAN masked, non-PAN unaffected, null message NEUTRAL, null format NEUTRAL, null format + params NEUTRAL, empty message NEUTRAL, multiple PANs masked

### Kafka Consumer Configuration

- [X] T017 Create `services/notification-service/src/main/java/com/creditcard/notification/config/KafkaConsumerConfig.java` — two `@Bean ConcurrentKafkaListenerContainerFactory` beans: `txStatusContainerFactory` (value deserializer for `TransactionStatusEvent`) and `fraudAlertContainerFactory` (value deserializer for `FraudAlertEvent`); both set `AckMode.MANUAL_IMMEDIATE`, `AUTO_OFFSET_RESET_CONFIG = "earliest"`, `USE_TYPE_HEADERS = false`

### Notification Channel Abstraction

- [X] T018 Create `services/notification-service/src/main/java/com/creditcard/notification/service/channel/NotificationChannelGateway.java` — interface with `NotificationChannel channel()` and `void send(String recipient, String subject, String body)`; `NotificationChannel` enum: `EMAIL`, `SMS`
- [X] T019 [P] Create `services/notification-service/src/main/java/com/creditcard/notification/service/channel/EmailGateway.java` — `@Component` stub implementing `NotificationChannelGateway`; `channel()` returns `EMAIL`; `send()` logs at INFO level with masked recipient; no actual SMTP call
- [X] T020 [P] Create `services/notification-service/src/main/java/com/creditcard/notification/service/channel/SmsGateway.java` — `@Component` stub implementing `NotificationChannelGateway`; `channel()` returns `SMS`; `send()` logs at INFO level; no actual SMS call
- [X] T021 [P] Write `services/notification-service/src/test/java/com/creditcard/notification/service/channel/EmailGatewayTest.java` — unit tests: `channel()` returns EMAIL, `send()` does not throw, recipient not logged in raw form
- [X] T022 [P] Write `services/notification-service/src/test/java/com/creditcard/notification/service/channel/SmsGatewayTest.java` — unit tests: `channel()` returns SMS, `send()` does not throw

**Checkpoint**: All entities, repositories, Kafka config, and channel abstraction in place — user stories can now proceed

---

## Phase 3: User Story 1 — Transaction Status Notifications (Priority: P1) 🎯 MVP

**Goal**: Consume `TransactionAuthorized`, `TransactionDeclined`, `TransactionFailed` events from `transaction.status.updated` and deliver EMAIL + SMS notifications to registered cardholders. Full idempotency; preference opt-out respected.

**Independent Test**: Publish `TransactionAuthorized` event for `vault:v1:tok_test` → assert 2 `NotificationRecord` rows (EMAIL + SMS) with status SENT; re-publish same event → still exactly 2 rows.

> **TDD: Write tests FIRST. All test tasks below MUST be written before implementation tasks and MUST fail (RED) initially.**

### Tests for User Story 1

- [X] T023 [US1] Create `services/notification-service/src/main/java/com/creditcard/notification/kafka/event/TransactionStatusEvent.java` — POJO with `eventId`, `eventType`, `transactionId`, `cardToken`, `reasonCode` (nullable), `occurredAt` (`Instant`); Jackson `@JsonIgnoreProperties(ignoreUnknown=true)`, `@JsonDeserialize` for Instant
- [X] T024 [US1] Create `services/notification-service/src/test/java/com/creditcard/notification/integration/TestKafkaConfig.java` — `@TestConfiguration` with `@Bean(name="txStatusKafkaTemplate") KafkaTemplate<String, TransactionStatusEvent>` using `JsonSerializer`; wired to `${spring.embedded.kafka.brokers}` via `@Value`
- [X] T025 [US1] Write `services/notification-service/src/test/java/com/creditcard/notification/integration/TransactionNotificationIntegrationTest.java` — `@SpringBootTest @EmbeddedKafka(topics={"transaction.status.updated","fraud.alert.raised"}) @Testcontainers @ActiveProfiles("test") @DirtiesContext`; seed `CardholderContact` in `@BeforeEach`; cover all 5 quickstart scenarios (1.1 authorized→2 SENT records, 1.2 declined with reasonCode, 1.3 no contact→0 records+1 processed_event, 1.4 notifications disabled→0 records, 1.5 duplicate x5→exactly 2 records); assert email gateway called once, SMS gateway called once using `@SpyBean`; content includes last4 from maskedCardNumber
- [X] T026 [P] [US1] Write `services/notification-service/src/test/java/com/creditcard/notification/service/NotificationServiceTest.java` — unit tests for: `maskEmail()` formats `j***@example.com`, `maskPhone()` formats `***-***-0000`, `buildTransactionSubject()` for AUTHORIZED/DECLINED/FAILED, `buildTransactionBody()` includes last4+transactionId+reasonCode, content never contains raw PAN pattern

### Implementation for User Story 1

- [X] T027 [US1] Create `services/notification-service/src/main/java/com/creditcard/notification/service/NotificationService.java` — `@Service`; inject `CardholderContactRepository`, `NotificationRecordRepository`, `ProcessedEventRepository`, `List<NotificationChannelGateway>`; implement `processTransactionEvent(TransactionStatusEvent)`: null guards → idempotency key `notif:{eventType}:{eventId}` → `insertIfAbsent()` → if 0 return → lookup contact → if absent save key+return → if `transactionNotificationsEnabled=false` save key+return → for each channel create PENDING `NotificationRecord` → commit `@Transactional` → call `gateway.send()` outside transaction → update status SENT/FAILED; implement `maskEmail()`, `maskPhone()`, `last4()` static helpers; implement `buildTransactionBody()` / `buildTransactionSubject()` from contract content table
- [X] T028 [US1] Create `services/notification-service/src/main/java/com/creditcard/notification/kafka/TransactionStatusListener.java` — `@Component`; `@KafkaListener(topics="transaction.status.updated", containerFactory="txStatusContainerFactory", groupId="${spring.kafka.consumer.group-id}")`; `onMessage(ConsumerRecord<String,TransactionStatusEvent> record, Acknowledgment ack)`: null payload guard → call `notificationService.processTransactionEvent()` → `finally { ack.acknowledge(); }`
- [X] T029 [US1] Run `./gradlew :services:notification-service:test jacocoTestReport jacocoTestCoverageVerification` — all US1 tests pass, JaCoCo 100% instruction + branch for `TransactionStatusListener`, `NotificationService` (transaction path), `TransactionStatusEvent`

**Checkpoint**: US1 fully functional — transaction notifications delivered; idempotency enforced; all 5 quickstart scenarios pass

---

## Phase 4: User Story 2 — Fraud Alert Notifications (Priority: P2)

**Goal**: Consume `FraudAlertRaised` events from `fraud.alert.raised` and deliver urgent EMAIL + SMS alerts to all registered channels regardless of `transactionNotificationsEnabled`. Multiple triggered rule types consolidated into one alert per channel.

**Independent Test**: Publish `FraudAlertRaised` with `transactionNotificationsEnabled=false` for cardholder → assert 2 SENT records; re-publish same event → still exactly 2 records.

> **TDD: Write tests FIRST. All test tasks below MUST be written before implementation tasks and MUST fail (RED) initially.**

### Tests for User Story 2

- [X] T030 [US2] Create `services/notification-service/src/main/java/com/creditcard/notification/kafka/event/FraudAlertEvent.java` — POJO with `eventId`, `eventType` (FraudAlertRaised), `alertId`, `transactionId`, `cardToken`, `triggeredRuleTypes` (`List<String>`), `detectedAt` (`Instant`), `sourceService`, `traceId`; Jackson annotations same as `TransactionStatusEvent`
- [X] T031 [US2] Update `services/notification-service/src/test/java/com/creditcard/notification/integration/TestKafkaConfig.java` — add `@Bean(name="fraudAlertKafkaTemplate") KafkaTemplate<String, FraudAlertEvent>` producer bean alongside existing `txStatusKafkaTemplate`
- [X] T032 [US2] Write `services/notification-service/src/test/java/com/creditcard/notification/integration/FraudAlertNotificationIntegrationTest.java` — `@SpringBootTest @EmbeddedKafka @Testcontainers @ActiveProfiles("test") @DirtiesContext`; cover all 3 quickstart scenarios: (2.1 email+phone+pref disabled→2 SENT, content includes "Suspicious activity" + rule types; 2.2 two rule types→1 notification per channel, both rules in content; 2.3 phone only→1 SMS record, email gateway never called); use `@SpyBean` on gateways to verify call counts
- [X] T033 [P] [US2] Update `services/notification-service/src/test/java/com/creditcard/notification/service/NotificationServiceTest.java` — add unit tests for: `buildFraudAlertSubject()` = "URGENT: Suspicious Activity Detected", `buildFraudAlertBody()` includes last4, comma-separated rule types, alertId; fraud processing ignores `transactionNotificationsEnabled`; multiple rule types → single body with all rules

### Implementation for User Story 2

- [X] T034 [US2] Add `processFraudAlertEvent(FraudAlertEvent)` to `services/notification-service/src/main/java/com/creditcard/notification/service/NotificationService.java` — null guards on `eventId` + `transactionId` → idempotency key `notif:FraudAlertRaised:{eventId}` → `insertIfAbsent()` → if 0 return → lookup contact → if absent save key+return → **no** preference check (fraud bypasses opt-out) → for each registered channel create PENDING record → commit → send → update SENT/FAILED; implement `buildFraudAlertBody()` / `buildFraudAlertSubject()` with comma-joined rule types
- [X] T035 [US2] Create `services/notification-service/src/main/java/com/creditcard/notification/kafka/FraudAlertListener.java` — `@Component`; `@KafkaListener(topics="fraud.alert.raised", containerFactory="fraudAlertContainerFactory", groupId="${spring.kafka.consumer.group-id}")`; `onMessage(ConsumerRecord<String,FraudAlertEvent> record, Acknowledgment ack)`; same null-guard + finally-ack pattern as `TransactionStatusListener`
- [X] T036 [US2] Run `./gradlew :services:notification-service:test jacocoTestReport jacocoTestCoverageVerification` — all US2 tests pass, JaCoCo 100% for `FraudAlertListener`, `FraudAlertEvent`, updated `NotificationService` fraud path

**Checkpoint**: US1 + US2 both functional — transaction and fraud alert notifications working independently

---

## Phase 5: User Story 3 — Notification Preferences Management (Priority: P3)

**Goal**: REST API (`POST/PUT/GET /api/v1/cardholder-contacts`) for registering and updating cardholder contact preferences per card token. Changes take effect immediately for subsequent events.

**Independent Test**: POST new contact → GET returns correct data → publish `TransactionAuthorized` → 2 SENT records with registered email/phone; PUT updates email → next event notification uses new address.

> **TDD: Write tests FIRST. All test tasks below MUST be written before implementation tasks and MUST fail (RED) initially.**

### Tests for User Story 3

- [X] T037 [P] [US3] Create `services/notification-service/src/main/java/com/creditcard/notification/web/dto/CardholderContactRequest.java` — record/class with `@NotBlank cardToken`, `@NotBlank maskedCardNumber` (reject if matches `\d{13,19}` pattern), `email` (optional, `@Email` if present), `phoneNumber` (optional, pattern `\+\d{7,15}` if present), `transactionNotificationsEnabled` (default true); custom `@AssertTrue` validator: at least one of email or phoneNumber non-null
- [X] T038 [P] [US3] Create `services/notification-service/src/main/java/com/creditcard/notification/web/dto/CardholderContactResponse.java` — mirrors `CardholderContact` fields; `registeredAt` and `updatedAt` as `Instant`
- [X] T039 [US3] Write `services/notification-service/src/test/java/com/creditcard/notification/web/CardholderContactControllerTest.java` — `@WebMvcTest(CardholderContactController.class)` with `@MockBean CardholderContactService`; test: POST valid → 201; POST missing cardToken → 400; POST no email+phone → 400; POST raw PAN as maskedCardNumber → 400; PUT valid → 200; GET existing → 200 with correct body; GET missing → 404; response body matches `CardholderContactResponse` schema
- [X] T040 [US3] Write `services/notification-service/src/test/java/com/creditcard/notification/integration/ContactManagementIntegrationTest.java` — `@SpringBootTest(webEnvironment=RANDOM_PORT) @Testcontainers @ActiveProfiles("test")`; cover quickstart scenarios 3.1–3.3: (3.1 register new contact→GET returns data; 3.2 PUT updates email→next event uses new email; 3.3 disable tx notifications→TransactionAuthorized produces 0 records, FraudAlertRaised produces 2 records)

### Implementation for User Story 3

- [X] T041 [US3] Create `services/notification-service/src/main/java/com/creditcard/notification/service/CardholderContactService.java` — `@Service`; inject `CardholderContactRepository`; `upsert(CardholderContactRequest)` → build/save `CardholderContact` with `registeredAt = NOW()` on insert, `updatedAt = NOW()` on update (JPA `@PreUpdate`); `findByCardToken(String)` → `Optional<CardholderContact>` → throw `CardholderContactNotFoundException` if absent
- [X] T042 [US3] Create `services/notification-service/src/main/java/com/creditcard/notification/web/CardholderContactController.java` — `@RestController @RequestMapping("/api/v1/cardholder-contacts")`; `@PostMapping` → 201 Created; `@PutMapping("/{cardToken}")` → 200 OK; `@GetMapping("/{cardToken}")` → 200 OK or 404; use `@Valid` on request body; `cardToken` in path URL-decoded; map entity to `CardholderContactResponse`
- [X] T043 [US3] Create `services/notification-service/src/main/java/com/creditcard/notification/web/GlobalExceptionHandler.java` — `@RestControllerAdvice`; handle `MethodArgumentNotValidException` → 400 with field errors; handle `CardholderContactNotFoundException` → 404 with `{"error":"No contact preferences found for card token"}`
- [X] T044 [US3] Run `./gradlew :services:notification-service:test jacocoTestReport jacocoTestCoverageVerification` — all US3 tests pass, JaCoCo 100% for `CardholderContactController`, `CardholderContactService`, `GlobalExceptionHandler`, DTOs

**Checkpoint**: All three user stories independently functional and tested

---

## Phase 6: Polish & Infrastructure

**Purpose**: Docker/k8s infrastructure and final full coverage gate verification

- [X] T045 Add `notification-service` service definition to `docker-compose.yml` — image from `services/notification-service/Dockerfile`, port mapping 8080:8080 and 8084:8084, environment variables for Kafka bootstrap and DB connection, depends_on kafka + postgres
- [X] T046 [P] Create `k8s/notification-service/deployment.yaml` — `Deployment` with 2 replicas, container image placeholder, env vars for KAFKA_BOOTSTRAP_SERVERS and DB, liveness/readiness probes on management port 8084 (`/actuator/health`), resource limits
- [X] T047 [P] Create `k8s/notification-service/service.yaml` — `ClusterIP` Service on port 8080, selector matching notification-service deployment labels
- [X] T048 Run `./gradlew :services:notification-service:test jacocoTestReport jacocoTestCoverageVerification` — full suite: all US1+US2+US3+foundational tests pass; JaCoCo 100% instruction + branch coverage gate passes across all production classes; zero exclusions

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately
- **Foundational (Phase 2)**: Depends on Phase 1 — BLOCKS all user stories
- **US1 (Phase 3)**: Depends on Foundational completion — no dependency on US2/US3
- **US2 (Phase 4)**: Depends on Foundational; US1 recommended first (shares `NotificationService.java`)
- **US3 (Phase 5)**: Depends on Foundational; independent of US1/US2 (separate REST path)
- **Polish (Phase 6)**: Depends on all user stories complete

### User Story Dependencies

- **US1 (P1)**: Independent after Foundational — **start here for MVP**
- **US2 (P2)**: Independent after Foundational; adds fraud path to `NotificationService.java` created in US1
- **US3 (P3)**: Fully independent REST API path — can be developed in parallel with US1/US2

### Within Each User Story

- Event POJO → Test infrastructure (TestKafkaConfig) → Integration test (RED) → Unit tests (RED) → Service implementation (GREEN) → Listener implementation (GREEN) → Coverage verification

### Parallel Opportunities

- T003/T004/T005 can run in parallel (Phase 1)
- T007/T008, T010/T011, T013/T014 can run in parallel (Phase 2)
- T018/T019/T020, T021/T022 can run in parallel (Phase 2)
- T026 can run in parallel with T025 (Phase 3 tests)
- T037/T038 can run in parallel (Phase 5 DTOs)
- T046/T047 can run in parallel (Phase 6 k8s)

---

## Parallel Example: User Story 1

```bash
# These can run in parallel once T023 (TransactionStatusEvent.java) is done:
Task T024: TestKafkaConfig.java
Task T025: TransactionNotificationIntegrationTest.java  [same test file — sequential]
Task T026: NotificationServiceTest.java  [different file — parallel with T025]

# Then implementation:
Task T027: NotificationService.java
Task T028: TransactionStatusListener.java  [depends on T027]
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (CRITICAL — blocks all stories)
3. Complete Phase 3: User Story 1 (T023–T029)
4. **STOP and VALIDATE**: All 5 quickstart scenarios 1.1–1.5 pass
5. JaCoCo gate passes for US1 classes

### Incremental Delivery

1. Phase 1 + Phase 2 → Infrastructure ready
2. Phase 3 (US1) → Transaction notifications MVP ✅ Demo-able
3. Phase 4 (US2) → Fraud alerts added ✅ Demo-able
4. Phase 5 (US3) → Preference management REST API ✅ Feature complete
5. Phase 6 → Docker/k8s + final coverage gate ✅ Release ready

---

## Notes

- `[P]` = different files, no incomplete dependencies — can run in parallel
- `[US?]` label maps task to specific user story for traceability
- All integration tests use `@DirtiesContext` (Kafka consumer lifecycle)
- `AUTO_OFFSET_RESET_CONFIG = "earliest"` MUST be set programmatically in `KafkaConsumerConfig` (not just YAML) for `@EmbeddedKafka` reliability
- `NotificationService.processTransactionEvent()` saves idempotency key even when no contact found (quickstart scenario 1.3 asserts `processed_events` has 1 row)
- Fraud alerts bypass `transactionNotificationsEnabled` — no preference check in `processFraudAlertEvent()`
- `TestKafkaConfig` uses named beans: `txStatusKafkaTemplate` and `fraudAlertKafkaTemplate` to prevent Spring bean name conflicts
- `maskEmail()` format: `j***@example.com` (first char + `***` + `@domain`)
- `maskPhone()` format: `***-***-NNNN` (last 4 digits preserved)
