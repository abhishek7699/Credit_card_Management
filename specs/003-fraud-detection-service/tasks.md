# Tasks: Fraud Detection Service

**Input**: Design documents from `/specs/003-fraud-detection-service/`

**Prerequisites**: plan.md ✅ | spec.md ✅ | research.md ✅ | data-model.md ✅ | contracts/ ✅ | quickstart.md ✅

**Tests**: TDD with 100% instruction + branch coverage is **MANDATORY** per spec and constitution.
Write tests FIRST, confirm they FAIL, then implement the minimum code to make them pass.

**Organization**: Tasks are grouped by user story to enable independent implementation and
testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no shared dependencies)
- **[Story]**: Which user story this task belongs to (US1, US2, US3)
- Exact file paths included in all descriptions

---

## Phase 1: Setup

**Purpose**: Gradle project scaffolding and repository wiring

- [X] T001 Create directory tree for `services/fraud-detection-service/` per plan.md (src/main/java, src/main/resources/db/migration, src/test/java, src/test/resources)
- [X] T002 Create `services/fraud-detection-service/build.gradle` with all dependencies: spring-boot-starter-data-jpa 3.2.5, spring-kafka, spring-boot-starter-actuator, flyway-core, postgresql driver, jackson-databind, micrometer-tracing-bridge-brave, zipkin-reporter-brave, logstash-logback-encoder:7.4, testcontainers:postgresql + junit-jupiter 1.19.7, spring-kafka-test, spring-boot-starter-test, mockito-core; JaCoCo 0.8.11 plugin with 100% instruction + branch coverage gate
- [X] T003 Update `settings.gradle` at repository root to add `include ':services:fraud-detection-service'`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: All shared infrastructure — config, migrations, events, idempotency.
**⚠️ CRITICAL**: No user story work begins until this phase is complete.

- [X] T004 [P] Create `services/fraud-detection-service/src/main/resources/application.yml`: spring.application.name=fraud-detection-service, datasource (url/username/password from env), jpa hibernate ddl-auto=validate + PostgreSQLDialect, kafka consumer group-id=fraud-detection-service-consumer + JsonDeserializer + trusted.packages=com.creditcard.frauddetection.kafka.event, kafka producer JsonSerializer + acks=all + enable.idempotence=true, flyway enabled, management server port 8083
- [X] T005 [P] Create `services/fraud-detection-service/src/main/resources/application-local.yml`: local datasource + kafka bootstrap overrides for docker-compose
- [X] T006 [P] Create `services/fraud-detection-service/src/test/resources/application-test.yml`: spring.flyway.enabled=true, spring.jpa.hibernate.ddl-auto=none
- [X] T007 [P] Create `services/fraud-detection-service/src/main/resources/logback-spring.xml`: LogstashEncoder JSON appender + PanMaskingFilter as TurboFilter, separate audit appender for card-related events
- [X] T008 [P] Create `src/main/resources/db/migration/V1__create_fraud_alerts.sql`: fraud_alerts table (id UUID PK, transaction_id UUID, card_token VARCHAR(512), amount DECIMAL(15,4), currency_code CHAR(3), triggered_rules TEXT, detected_at TIMESTAMPTZ DEFAULT now()); indexes on transaction_id and card_token
- [X] T009 [P] Create `src/main/resources/db/migration/V2__create_fraud_rule_configs.sql`: fraud_rule_configs table (rule_type VARCHAR(50) PK, enabled BOOLEAN DEFAULT true, parameters TEXT DEFAULT '{}', updated_at TIMESTAMPTZ DEFAULT now()); INSERT seed rows for VELOCITY_EXCEEDED, AMOUNT_ANOMALY, BLACKLISTED_CARD
- [X] T010 [P] Create `src/main/resources/db/migration/V3__create_blacklist_entries.sql`: blacklist_entries table (card_token VARCHAR(512) PK, reason VARCHAR(255), added_at TIMESTAMPTZ DEFAULT now())
- [X] T011 [P] Create `src/main/resources/db/migration/V4__create_transaction_records.sql`: transaction_records table (id UUID PK, transaction_id UUID NOT NULL UNIQUE, card_token VARCHAR(512), amount DECIMAL(15,4), currency_code CHAR(3), occurred_at TIMESTAMPTZ); composite index idx_tx_records_card_time(card_token, occurred_at)
- [X] T012 [P] Create `src/main/resources/db/migration/V5__create_processed_events.sql`: processed_events table (event_key VARCHAR(255) PK, processed_at TIMESTAMPTZ DEFAULT now())
- [X] T013 Create `src/main/java/com/creditcard/frauddetection/FraudDetectionServiceApplication.java`: @SpringBootApplication main class
- [X] T014 Create `src/main/java/com/creditcard/frauddetection/config/KafkaConsumerConfig.java`: @Configuration @EnableKafka; DefaultKafkaConsumerFactory with props map including AUTO_OFFSET_RESET_CONFIG="earliest", ENABLE_AUTO_COMMIT_CONFIG=false, ISOLATION_LEVEL_CONFIG="read_committed", StringDeserializer + JsonDeserializer; ConcurrentKafkaListenerContainerFactory with AckMode.MANUAL_IMMEDIATE
- [X] T015 [P] Create `src/main/java/com/creditcard/frauddetection/config/KafkaProducerConfig.java`: @Configuration; DefaultKafkaProducerFactory with StringSerializer + JsonSerializer; KafkaTemplate bean; producer props: acks=all, enable.idempotence=true, retries=3
- [X] T016 [P] Create `src/main/java/com/creditcard/frauddetection/config/logging/PanMaskingFilter.java`: Logback TurboFilter; DENY if message or formatted args contain 13–19 consecutive digits; NEUTRAL otherwise (identical pattern to authorization-service)
- [X] T017 [P] Create `src/main/java/com/creditcard/frauddetection/kafka/event/TransactionInitiatedEvent.java`: POJO with eventId String, eventType String, transactionId UUID, cardToken String, amount BigDecimal, currencyCode String, occurredAt Instant; getters/setters; default constructor
- [X] T018 [P] Create `src/main/java/com/creditcard/frauddetection/kafka/event/FraudAlertRaisedEvent.java`: POJO with eventId UUID, eventType String, alertId UUID, transactionId UUID, cardToken String, triggeredRuleTypes List<String>, detectedAt Instant, sourceService String, traceId String; static factory method `of(FraudAlert alert, String traceId)`; getters/setters
- [X] T019 Create `src/main/java/com/creditcard/frauddetection/domain/ProcessedEvent.java`: @Entity @Table(name="processed_events"); eventKey String @Id, processedAt Instant; getters/setters
- [X] T020 Create `src/main/java/com/creditcard/frauddetection/repository/ProcessedEventRepository.java`: extends JpaRepository; @Modifying @Query native INSERT INTO processed_events (event_key, processed_at) VALUES (:eventKey, now()) ON CONFLICT DO NOTHING; returns int
- [X] T021 Write `src/test/java/com/creditcard/frauddetection/domain/ProcessedEventTest.java`: test sets eventKey and processedAt via setters and asserts getters return same values; confirm test FAILS before T019 is implemented
- [X] T022 Write `src/test/java/com/creditcard/frauddetection/service/IdempotencyServiceTest.java`: buildKey("TransactionInitiated", "evt-001") returns "fraud:TransactionInitiated:evt-001"; isDuplicate returns false when repository.insertIfAbsent returns 1; isDuplicate returns true when repository.insertIfAbsent returns 0; confirm tests FAIL before T023 is implemented
- [X] T023 Implement `src/main/java/com/creditcard/frauddetection/service/IdempotencyService.java`: buildKey(String type, String id) → "fraud:{type}:{id}"; isDuplicate(String key) → calls processedEventRepository.insertIfAbsent(key), returns true if result == 0

**Checkpoint**: Foundation ready — Gradle builds, Flyway migrations defined, Kafka config wired, idempotency service ready. User story implementation can now begin.

---

## Phase 3: User Story 1 — Suspicious Transaction Detection (Priority: P1) 🎯 MVP

**Goal**: Every incoming transaction is evaluated against all active fraud rules. When one or more
rules trigger, a fraud alert is persisted and a FraudAlertRaised event is published. Clean
transactions produce no output event.

**Independent Test**: Publish a transaction that exceeds the velocity threshold → verify 1 alert row
in fraud_alerts and 1 message on `fraud.alert.raised`. Publish a clean transaction → verify 0 alert
rows and 0 messages.

### TDD: Write Failing Tests First ⚠️

> **Write these tests and confirm they FAIL before writing any implementation code.**

- [X] T024 [P] [US1] Write `src/test/java/com/creditcard/frauddetection/domain/FraudAlertTest.java`: set/get all fields; TriggeredRulesConverter converts ["VELOCITY_EXCEEDED","BLACKLISTED_CARD"] → "VELOCITY_EXCEEDED,BLACKLISTED_CARD" and back; single element; empty list
- [X] T025 [P] [US1] Write `src/test/java/com/creditcard/frauddetection/domain/FraudRuleConfigTest.java`: set/get ruleType, enabled, parameters, updatedAt
- [X] T026 [P] [US1] Write `src/test/java/com/creditcard/frauddetection/domain/BlacklistEntryTest.java`: set/get cardToken, reason, addedAt
- [X] T027 [P] [US1] Write `src/test/java/com/creditcard/frauddetection/domain/TransactionRecordTest.java`: set/get all fields (id, transactionId, cardToken, amount, currencyCode, occurredAt)
- [X] T028 [P] [US1] Write `src/test/java/com/creditcard/frauddetection/rules/VelocityRuleTest.java`: fires when txCount >= maxTransactions; no fire when txCount < maxTransactions; returns Optional.empty() when rule disabled; returns Optional.empty() when config not found; uses correct rule type name "VELOCITY_EXCEEDED"
- [X] T029 [P] [US1] Write `src/test/java/com/creditcard/frauddetection/rules/AmountAnomalyRuleTest.java`: fires when amount > multiplier × avg; no fire when amount <= multiplier × avg; uses absoluteCeiling as baseline when no transaction history; no fire when rule disabled; returns Optional.empty() when config missing
- [X] T030 [P] [US1] Write `src/test/java/com/creditcard/frauddetection/rules/BlacklistRuleTest.java`: fires when card token in blacklist_entries; no fire when card not listed; no fire when rule disabled; no fire when config missing; correct rule type name "BLACKLISTED_CARD"
- [X] T031 [US1] Write `src/test/java/com/creditcard/frauddetection/service/FraudDetectionServiceTest.java`: clean transaction (all rules return empty) → Optional.empty() + TransactionRecord saved; one rule fires → Optional<FraudAlertRaisedEvent> with that ruleType; three rules fire → single event with all three ruleTypes in triggeredRuleTypes; rule throws exception → safeEvaluate returns empty (fail-safe, warning logged); FraudAlert not saved for clean transactions
- [X] T032 [US1] Write `src/test/java/com/creditcard/frauddetection/kafka/TransactionInitiatedListenerTest.java`: fraud detected → kafkaTemplate.send("fraud.alert.raised", ...) + ack.acknowledge(); clean event → no kafkaTemplate.send + ack.acknowledge(); duplicate event (isDuplicate=true) → no service call, no publish, ack; null payload → log warn + ack; null transactionId on event → log warn + ack; service throws RuntimeException → log warn + ack, no publish
- [X] T033 [P] [US1] Write `src/test/java/com/creditcard/frauddetection/contract/KafkaEventSchemaTest.java`: serialize TransactionInitiatedEvent to JSON and back; verify all fields survive round-trip; serialize FraudAlertRaisedEvent; verify required fields eventId, alertId, transactionId, cardToken, triggeredRuleTypes, detectedAt present and non-null
- [X] T034 [US1] Write `src/test/java/com/creditcard/frauddetection/integration/FraudDetectionIntegrationTest.java` (@SpringBootTest @EmbeddedKafka + @DirtiesContext, Testcontainers PostgreSQL): scenario 1.1 velocity rule triggers (seed 3 tx_records at threshold, publish 4th → assert fraud_alerts row + message on fraud.alert.raised with VELOCITY_EXCEEDED); scenario 1.2 amount anomaly triggers (seed history avg=100, publish amount=400 with multiplier=3 → alert with AMOUNT_ANOMALY); scenario 1.3 blacklist triggers (insert card into blacklist_entries → publish event → alert with BLACKLISTED_CARD); scenario 1.4 clean transaction (all rules disabled or under threshold → 0 alerts, 0 events on fraud.alert.raised); scenario 1.5 two rules trigger simultaneously → exactly 1 fraud_alerts row with both rule types in triggered_rules

### Implementation

- [X] T035 [P] [US1] Implement `src/main/java/com/creditcard/frauddetection/domain/FraudAlert.java`: @Entity @Table("fraud_alerts"); UUID id @Id, UUID transactionId, String cardToken, BigDecimal amount, String currencyCode, List<String> triggeredRules (via TriggeredRulesConverter), Instant detectedAt; inner static @Converter class TriggeredRulesConverter implementing AttributeConverter<List<String>,String> (join/split on comma); getters/setters
- [X] T036 [P] [US1] Implement `src/main/java/com/creditcard/frauddetection/domain/FraudRuleConfig.java`: @Entity @Table("fraud_rule_configs"); String ruleType @Id, boolean enabled, String parameters, Instant updatedAt; getters/setters
- [X] T037 [P] [US1] Implement `src/main/java/com/creditcard/frauddetection/domain/BlacklistEntry.java`: @Entity @Table("blacklist_entries"); String cardToken @Id, String reason, Instant addedAt; getters/setters
- [X] T038 [P] [US1] Implement `src/main/java/com/creditcard/frauddetection/domain/TransactionRecord.java`: @Entity @Table("transaction_records"); UUID id @Id @GeneratedValue, UUID transactionId @Column(unique=true), String cardToken, BigDecimal amount, String currencyCode, Instant occurredAt; getters/setters
- [X] T039 [P] [US1] Implement `src/main/java/com/creditcard/frauddetection/repository/FraudAlertRepository.java`: extends JpaRepository<FraudAlert, UUID>; no custom methods required
- [X] T040 [P] [US1] Implement `src/main/java/com/creditcard/frauddetection/repository/FraudRuleConfigRepository.java`: extends JpaRepository<FraudRuleConfig, String>; Optional<FraudRuleConfig> findByRuleType(String ruleType) (inherits from JpaRepository findById — use findById instead)
- [X] T041 [P] [US1] Implement `src/main/java/com/creditcard/frauddetection/repository/BlacklistEntryRepository.java`: extends JpaRepository<BlacklistEntry, String>; boolean existsByCardToken(String cardToken)
- [X] T042 [P] [US1] Implement `src/main/java/com/creditcard/frauddetection/repository/TransactionRecordRepository.java`: extends JpaRepository<TransactionRecord, UUID>; long countByCardTokenAndOccurredAtAfter(String cardToken, Instant cutoff); @Query("SELECT AVG(t.amount) FROM TransactionRecord t WHERE t.cardToken = :cardToken AND t.occurredAt > :cutoff") Optional<BigDecimal> findAverageAmountByCardTokenAndOccurredAtAfter(@Param String cardToken, @Param Instant cutoff)
- [X] T043 [US1] Implement `src/main/java/com/creditcard/frauddetection/rules/FraudRule.java`: interface with single method `Optional<String> evaluate(TransactionInitiatedEvent event)`
- [X] T044 [P] [US1] Implement `src/main/java/com/creditcard/frauddetection/rules/VelocityRule.java`: @Component; constructor-inject FraudRuleConfigRepository + TransactionRecordRepository + ObjectMapper; evaluate() reads config by ruleType "VELOCITY_EXCEEDED"; returns empty if disabled or missing; parses params to VelocityParams record(int maxTransactions, int windowMinutes); counts tx records in window; returns Optional.of("VELOCITY_EXCEEDED") if count >= maxTransactions
- [X] T045 [P] [US1] Implement `src/main/java/com/creditcard/frauddetection/rules/AmountAnomalyRule.java`: @Component; constructor-inject FraudRuleConfigRepository + TransactionRecordRepository + ObjectMapper; evaluate() reads config "AMOUNT_ANOMALY"; parses AnomalyParams record(double multiplier, int lookbackDays, BigDecimal absoluteCeiling); queries average over lookback window; baseline = avg.orElse(absoluteCeiling); returns Optional.of("AMOUNT_ANOMALY") if amount.compareTo(baseline.multiply(BigDecimal.valueOf(multiplier))) > 0
- [X] T046 [P] [US1] Implement `src/main/java/com/creditcard/frauddetection/rules/BlacklistRule.java`: @Component; constructor-inject FraudRuleConfigRepository + BlacklistEntryRepository; evaluate() reads config "BLACKLISTED_CARD"; returns empty if disabled or missing; returns Optional.of("BLACKLISTED_CARD") if blacklistEntryRepository.existsByCardToken(event.getCardToken())
- [X] T047 [US1] Implement `src/main/java/com/creditcard/frauddetection/service/FraudDetectionService.java`: @Service; constructor-inject List<FraudRule> rules + FraudAlertRepository + TransactionRecordRepository + ObjectMapper; @Transactional evaluate(TransactionInitiatedEvent event): collect triggered = rules.stream().map(r -> safeEvaluate(r,event)).filter(Optional::isPresent).map(Optional::get).toList(); save TransactionRecord (always); if triggered non-empty save FraudAlert and return Optional.of(buildFraudAlertRaisedEvent(alert, MDC traceId)); else return Optional.empty(); private safeEvaluate wraps in try-catch logging WARN on exception
- [X] T048 [US1] Implement `src/main/java/com/creditcard/frauddetection/kafka/TransactionInitiatedListener.java`: @Component; constructor-inject FraudDetectionService + IdempotencyService + KafkaTemplate; @KafkaListener(topics="transaction.initiated") onMessage(ConsumerRecord, Acknowledgment): null/invalid payload guard → log WARN + ack + return; null transactionId guard → log WARN + ack + return; idempotency check → if isDuplicate log DEBUG + ack + return; call fraudDetectionService.evaluate(event); alertEvent.ifPresent(e -> kafkaTemplate.send("fraud.alert.raised", e.getTransactionId().toString(), e)); ack.acknowledge()
- [X] T049 [US1] Run `./gradlew :services:fraud-detection-service:test` and verify all Phase 3 unit and integration tests pass; confirm JaCoCo gate passes for all US1 classes

**Checkpoint**: User Story 1 complete — velocity, anomaly, and blacklist detection all work end-to-end; clean transactions produce no output; single alert per transaction regardless of how many rules fire.

---

## Phase 4: User Story 2 — Configurable Fraud Rules (Priority: P2)

**Goal**: Risk managers can update rule thresholds (velocity window/count, anomaly multiplier/ceiling,
blacklist entries) in CockroachDB and changes take effect for the next transaction without
redeploying the service.

**Independent Test**: Update velocity maxTransactions in fraud_rule_configs, publish a transaction
that would have been clean under the old threshold but triggers under the new threshold, verify
alert raised — all without restarting the service.

### TDD: Write Failing Tests First ⚠️

- [X] T050 [P] [US2] Extend `FraudDetectionIntegrationTest.java` with scenario 2.1: seed VELOCITY_EXCEEDED config with maxTransactions=5; seed 4 tx_records in window; publish event → assert no alert; UPDATE fraud_rule_configs set parameters='{"maxTransactions":3,"windowMinutes":10}'; publish another event (new eventId/transactionId) → assert alert with VELOCITY_EXCEEDED
- [X] T051 [P] [US2] Extend `FraudDetectionIntegrationTest.java` with scenario 2.2: card not blacklisted initially → publish event → assert no alert; INSERT card into blacklist_entries; publish new event for same card → assert alert with BLACKLISTED_CARD; DELETE from blacklist_entries; publish new event → assert no alert
- [X] T052 [P] [US2] Extend `FraudDetectionIntegrationTest.java` with scenario 2.3: AMOUNT_ANOMALY config multiplier=1.0, absoluteCeiling=500.00; card has zero tx history; publish event with amount=600.00 → assert alert with AMOUNT_ANOMALY; publish event with amount=400.00 (new eventId) → assert no alert
- [X] T053 [US2] Run `./gradlew :services:fraud-detection-service:test` and verify US2 integration tests pass — no new production code required (rules already read fresh config per evaluation)

**Checkpoint**: User Story 2 complete — rule threshold changes and blacklist mutations take effect immediately on next evaluation without restart.

---

## Phase 5: User Story 3 — Exactly-Once Processing (Priority: P3)

**Goal**: Duplicate or redelivered TransactionInitiated events for the same transaction are silently
discarded after first successful processing. No duplicate alerts, no duplicate Kafka events.

**Independent Test**: Deliver the same suspicious event 10 times; verify exactly 1 fraud_alerts row,
at most 1 message on fraud.alert.raised, and exactly 1 processed_events row.

### TDD: Write Failing Tests First ⚠️

- [X] T054 [US3] Write `src/test/java/com/creditcard/frauddetection/integration/IdempotencyIntegrationTest.java` (@SpringBootTest @EmbeddedKafka + @DirtiesContext, Testcontainers PostgreSQL): scenario 3.1 (publish same suspicious event with identical eventId 10 times → assert exactly 1 row in fraud_alerts + exactly 1 row in processed_events + at most 1 message on fraud.alert.raised); scenario 3.2 (publish same clean event twice → assert 0 fraud_alerts + 1 processed_events); scenario 3.3 (publish ConsumerRecord with null value payload → assert listener does not throw, 0 fraud_alerts, 0 processed_events, offset acknowledged)
- [X] T055 [US3] Run `./gradlew :services:fraud-detection-service:test` and verify US3 idempotency tests pass — idempotency mechanism was built in Phase 2 (IdempotencyService + ProcessedEventRepository) and wired into the listener in US1 (T048)

**Checkpoint**: User Story 3 complete — exactly-once guarantee proven by integration test; 10 duplicate deliveries produce 1 alert.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Container packaging, infrastructure manifests, final coverage verification.

- [X] T056 Create `services/fraud-detection-service/Dockerfile`: multi-stage build (gradle:8-jdk17 build stage → eclipse-temurin:17-jre-jammy runtime stage); copy built JAR; EXPOSE 8080 8083; ENTRYPOINT java -jar
- [X] T057 Update `docker-compose.yml` at repository root: add fraud-detection-service service definition (image: fraud-detection-service:latest, build context, depends_on: cockroachdb + kafka, environment: SPRING_DATASOURCE_URL, SPRING_KAFKA_BOOTSTRAP_SERVERS, SPRING_PROFILES_ACTIVE=local, ports: 8080:8080 + 8083:8083)
- [X] T058 [P] Create `k8s/fraud-detection-service/deployment.yaml`: Deployment with 2 replicas, container image placeholder, env vars from Kubernetes Secrets (SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, SPRING_DATASOURCE_PASSWORD, SPRING_KAFKA_BOOTSTRAP_SERVERS), liveness + readiness probes on /actuator/health/liveness and /actuator/health/readiness port 8083
- [X] T059 [P] Create `k8s/fraud-detection-service/service.yaml`: ClusterIP Service exposing port 8080 (app) and 8083 (management)
- [X] T060 Run full final build: `./gradlew :services:fraud-detection-service:cleanTest :services:fraud-detection-service:test :services:fraud-detection-service:jacocoTestReport :services:fraud-detection-service:jacocoTestCoverageVerification` — verify BUILD SUCCESSFUL with 100% instruction + branch coverage across all classes

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately
- **Foundational (Phase 2)**: Depends on Phase 1 completion — **BLOCKS all user story work**
- **User Story 1 (Phase 3)**: Depends on Phase 2; is the primary prerequisite for phases 4 and 5
- **User Story 2 (Phase 4)**: Depends on Phase 3 completion (tests exercise US1 rule components)
- **User Story 3 (Phase 5)**: Depends on Phase 3 completion (tests exercise US1 listener + Phase 2 idempotency)
- **Polish (Phase 6)**: Depends on all user stories complete

### User Story Dependencies

- **US1 (P1)**: Starts after Foundational (Phase 2) — provides all domain entities, rules, and service
- **US2 (P2)**: Starts after US1 — adds integration tests only; no new production code required
- **US3 (P3)**: Starts after US1 — adds idempotency integration tests only; mechanism already built

### Within User Story 1

- All test tasks marked [P] (T024–T030, T033) can be written in parallel
- T031, T032, T034 written after unit tests (need full service API to be known)
- All entity + repository implementations marked [P] (T035–T042) can run in parallel
- T043 (FraudRule interface) must complete before T044–T046 (rule implementations)
- T047 (FraudDetectionService) depends on T043–T046 (rules) and T035–T042 (entities/repos)
- T048 (Listener) depends on T047 (service) and T023 (IdempotencyService)
- T049 (verify tests pass) runs last in Phase 3

---

## Parallel Example: User Story 1

```bash
# Parallel: Write all domain entity tests together
T024: FraudAlertTest.java
T025: FraudRuleConfigTest.java
T026: BlacklistEntryTest.java
T027: TransactionRecordTest.java

# Parallel: Write all rule unit tests together (after understanding domain)
T028: VelocityRuleTest.java
T029: AmountAnomalyRuleTest.java
T030: BlacklistRuleTest.java
T033: KafkaEventSchemaTest.java

# Parallel: Implement all entities together
T035: FraudAlert.java
T036: FraudRuleConfig.java
T037: BlacklistEntry.java
T038: TransactionRecord.java

# Parallel: Implement all repositories together
T039: FraudAlertRepository.java
T040: FraudRuleConfigRepository.java
T041: BlacklistEntryRepository.java
T042: TransactionRecordRepository.java

# Parallel: Implement all rules together (after T043 interface)
T044: VelocityRule.java
T045: AmountAnomalyRule.java
T046: BlacklistRule.java
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (**CRITICAL** — blocks all stories)
3. Write failing tests for US1 (T024–T034)
4. Implement US1 (T035–T048)
5. **STOP and VALIDATE**: Run `./gradlew :services:fraud-detection-service:test` — all green + coverage gate passes
6. User Story 1 is a shippable increment

### Incremental Delivery

1. Setup + Foundational → project builds, DB migrates, idempotency ready
2. US1 → core detection works end-to-end (MVP)
3. US2 → integration tests confirm configurability (no new code)
4. US3 → integration tests confirm idempotency at scale (no new code)
5. Polish → containerized, k8s-ready

---

## Notes

- [P] tasks target different files and have no dependency on incomplete tasks
- Constitution Principle II: write test first, confirm it FAILS, then implement
- Constitution Principle III: idempotency key format `fraud:TransactionInitiated:{eventId}`
- Constitution Principle I: card tokens are opaque strings; no PAN ever enters this service
- `AUTO_OFFSET_RESET_CONFIG = "earliest"` MUST be set in KafkaConsumerConfig Java class (not just yaml) — critical for EmbeddedKafka test reliability (see research.md Decision 6)
- `application-test.yml` uses `ddl-auto: none` — Flyway manages schema; Hibernate must not interfere
- `@DirtiesContext` required on integration test classes that use `@EmbeddedKafka`
- JaCoCo exclusions are NOT permitted — 100% coverage must be achieved for every class
- Do not commit until tests pass for the current task
