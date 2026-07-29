# Implementation Plan: Fraud Detection Service

**Branch**: `003-fraud-detection-service` | **Date**: 2026-06-12 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/003-fraud-detection-service/spec.md`

## Summary

The `fraud-detection-service` is the third microservice in the Credit Card Payment Gateway.
It consumes `TransactionInitiated` Kafka events, evaluates each against three configurable
fraud rules (velocity check, amount anomaly, blacklist), persists a `FraudAlert` to
CockroachDB when one or more rules trigger, and publishes a `FraudAlertRaised` event to
the `fraud.alert.raised` topic. Clean transactions produce no output event. Processing is
idempotent via a `processed_events` table. A `transaction_records` table stores the history
needed by velocity and anomaly rules. Rule thresholds are stored in `fraud_rule_configs` and
read on each evaluation — no restart required for threshold changes.

Technical approach: Spring Boot 3.x Kafka consumer + producer; Strategy pattern for fraud
rules (one Spring @Component per rule); Spring Data JPA on CockroachDB Serverless; Flyway
migrations; JUnit 5 + Mockito + Testcontainers for 100% TDD coverage; JaCoCo gate;
structured JSON logging via `logstash-logback-encoder`; Micrometer trace propagation via
Kafka headers. No gRPC server (Kafka-only inter-service communication). No Spring Retry
(insert-only operations, no concurrent writes to shared mutable state).

## Technical Context

**Language/Version**: Java 17 (LTS)

**Primary Dependencies**:
- `org.springframework.boot:spring-boot-starter-data-jpa` (3.2.5)
- `org.springframework.kafka:spring-kafka`
- `org.springframework.boot:spring-boot-starter-actuator`
- `org.flywaydb:flyway-core`
- `org.postgresql:postgresql` (CockroachDB PostgreSQL wire protocol)
- `com.fasterxml.jackson.core:jackson-databind` (JSON parameter parsing for rule configs)
- `io.micrometer:micrometer-tracing-bridge-brave`
- `io.zipkin.reporter2:zipkin-reporter-brave`
- `net.logstash.logback:logstash-logback-encoder:7.4`
- `org.testcontainers:postgresql`, `org.testcontainers:junit-jupiter` (1.19.7)
- `org.springframework.kafka:spring-kafka-test`
- `org.springframework.boot:spring-boot-starter-test`
- `org.mockito:mockito-core`
- JaCoCo Gradle plugin `0.8.11`

**Storage**: CockroachDB Serverless — PostgreSQL-compatible wire protocol; accessed via
Spring Data JPA with `org.postgresql` driver. Five tables: `fraud_alerts`,
`fraud_rule_configs`, `blacklist_entries`, `transaction_records`, `processed_events`.
No concurrent updates to shared mutable state → no Spring Retry or optimistic locking needed.
Hikari connection pool.

**Testing**: JUnit 5 + Mockito (unit); `@SpringBootTest` + Testcontainers PostgreSQL +
`@EmbeddedKafka` (integration); Kafka event schema assertions (contract tests). JaCoCo
enforces 100% instruction + branch coverage as a Gradle build gate.

**Target Platform**: Docker container → Linux pod on Kubernetes / GKE (production).
Local dev via `docker-compose up` (CockroachDB, Kafka/KRaft). No Vault dependency
(service only receives opaque card tokens; no decryption needed).

**Project Type**: Microservice — Kafka consumer + Kafka producer (no gRPC server in v1)

**Performance Goals**: Fraud evaluation completed and published within 500 ms p95
end-to-end (SC-001). 500 transaction events/second sustained throughput (SC-005).

**Constraints**:
- 100% line and branch test coverage — JaCoCo gate; CI/CD fails if not met
- Zero raw card numbers in storage, logs, or Kafka events at any time (Principle I)
- Fail-safe: rule evaluation errors log warning and treat rule as not-triggered (FR-011)
- Malformed events logged and discarded; no alert raised, no halt (FR-010)
- Kafka offset committed only after all DB writes succeed and result event published (if any)
- fraud-detection-service owns its own CockroachDB schema; no cross-service table access
- Rule threshold changes effective within 60 s without restart (SC-004, FR-009)

**Scale/Scope**: `fraud-detection-service` only — third of 7 services in the platform.
Management port: `8083`. Kafka topics consumed: `transaction.initiated`.
Kafka topics produced: `fraud.alert.raised`.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Pre-Design | Post-Design | Evidence |
|-----------|-----------|-------------|----------|
| **I. Tokenization & Zero Raw Card Data** | ✅ PASS | ✅ PASS | Service only receives opaque card tokens; no PAN ever enters this service; `PanMaskingFilter` (defense-in-depth) blocks 13–19 digit sequences from logs; all DB columns and Kafka events use `card_token` (opaque string) |
| **II. Test-First / 100% Coverage** | ✅ PASS | ✅ PASS | TDD mandated; JaCoCo 100% instruction + branch gate in `build.gradle`; all tests written and confirmed failing before implementation; unit (Mockito) + integration (Testcontainers + EmbeddedKafka) + contract (event schema assertions) |
| **III. Idempotent Event Consumers** | ✅ PASS | ✅ PASS | `processed_events` table keyed by `fraud:{eventType}:{eventId}`; atomic `ON CONFLICT DO NOTHING` insert; committed in same DB transaction as `transaction_record` and `fraud_alert` (if any); Kafka offset committed only after all writes succeed |
| **IV. Iterative One-Step Development** | ✅ PASS | ✅ PASS | `fraud-detection-service` is Service #3; `authorization-service` (Service #2) is complete and approved; one class at a time, one method at a time; no stubs for future services |
| **V. Layered Architecture & Communication Tiers** | ✅ PASS | ✅ PASS | API Layer: `TransactionInitiatedListener`; Service Layer: `FraudDetectionService`, `IdempotencyService`; Rules Layer: `VelocityRule`, `AmountAnomalyRule`, `BlacklistRule`; Repository Layer: `FraudAlertRepository`, `FraudRuleConfigRepository`, `BlacklistEntryRepository`, `TransactionRecordRepository`, `ProcessedEventRepository`; Kafka-only inter-service communication |
| **VI. Observability & Audit Trail** | ✅ PASS | ✅ PASS | Structured JSON audit log (`logstash-logback-encoder`); Micrometer `traceId` propagated from inbound Kafka header → outbound Kafka header + MDC; `PanMaskingFilter` prevents raw card data in logs; every fraud detection decision logged with masked card token, triggered rules, and timestamp |
| **VII. Proto-First Service Contracts** | ✅ PASS | ✅ PASS | No gRPC interface in v1 — Principle VII not triggered; Kafka event contracts documented in `contracts/`; if gRPC is added in a future iteration, a `.proto` file must be defined and approved before implementation begins |
| **VIII. Frontend Code Sharing** | ✅ N/A | ✅ N/A | Backend service; no frontend impact |

**Gate result: ALL 8 PRINCIPLES PASS — Phase 0 research and Phase 1 design authorized.**

## Project Structure

### Documentation (this feature)

```text
specs/003-fraud-detection-service/
├── plan.md                   # This file
├── spec.md                   # Feature specification
├── research.md               # Phase 0 output
├── data-model.md             # Phase 1 output
├── quickstart.md             # Phase 1 output
├── contracts/
│   ├── kafka-consumer.md     # TransactionInitiated event contract (Phase 1 output)
│   └── kafka-producer.md     # FraudAlertRaised event contract (Phase 1 output)
└── tasks.md                  # Phase 2 output — created by /speckit-tasks
```

### Source Code (repository root)

Multi-project Gradle build. The fraud-detection-service is an independent subproject under
`services/` alongside transaction-service and authorization-service.

```text
credit-card-payment-gateway/              (repository root)
├── settings.gradle                       # ADD: include ':services:fraud-detection-service'
├── services/
│   ├── transaction-service/              # Service #1 (complete)
│   ├── authorization-service/            # Service #2 (complete)
│   └── fraud-detection-service/          # Service #3 (this feature)
│       ├── build.gradle
│       ├── Dockerfile
│       └── src/
│           ├── main/
│           │   ├── java/com/creditcard/frauddetection/
│           │   │   ├── FraudDetectionServiceApplication.java
│           │   │   ├── config/
│           │   │   │   ├── KafkaConsumerConfig.java
│           │   │   │   ├── KafkaProducerConfig.java
│           │   │   │   └── logging/
│           │   │   │       └── PanMaskingFilter.java
│           │   │   ├── domain/
│           │   │   │   ├── FraudAlert.java
│           │   │   │   ├── FraudRuleConfig.java
│           │   │   │   ├── BlacklistEntry.java
│           │   │   │   ├── TransactionRecord.java
│           │   │   │   └── ProcessedEvent.java
│           │   │   ├── kafka/
│           │   │   │   ├── event/
│           │   │   │   │   ├── TransactionInitiatedEvent.java
│           │   │   │   │   └── FraudAlertRaisedEvent.java
│           │   │   │   └── TransactionInitiatedListener.java
│           │   │   ├── repository/
│           │   │   │   ├── FraudAlertRepository.java
│           │   │   │   ├── FraudRuleConfigRepository.java
│           │   │   │   ├── BlacklistEntryRepository.java
│           │   │   │   ├── TransactionRecordRepository.java
│           │   │   │   └── ProcessedEventRepository.java
│           │   │   ├── rules/
│           │   │   │   ├── FraudRule.java
│           │   │   │   ├── VelocityRule.java
│           │   │   │   ├── AmountAnomalyRule.java
│           │   │   │   └── BlacklistRule.java
│           │   │   └── service/
│           │   │       ├── FraudDetectionService.java
│           │   │       └── IdempotencyService.java
│           │   └── resources/
│           │       ├── application.yml
│           │       ├── application-local.yml
│           │       ├── logback-spring.xml
│           │       └── db/
│           │           └── migration/
│           │               ├── V1__create_fraud_alerts.sql
│           │               ├── V2__create_fraud_rule_configs.sql
│           │               ├── V3__create_blacklist_entries.sql
│           │               ├── V4__create_transaction_records.sql
│           │               └── V5__create_processed_events.sql
│           └── test/
│               ├── java/com/creditcard/frauddetection/
│               │   ├── domain/
│               │   │   ├── FraudAlertTest.java
│               │   │   ├── FraudRuleConfigTest.java
│               │   │   ├── BlacklistEntryTest.java
│               │   │   ├── TransactionRecordTest.java
│               │   │   └── ProcessedEventTest.java
│               │   ├── rules/
│               │   │   ├── VelocityRuleTest.java
│               │   │   ├── AmountAnomalyRuleTest.java
│               │   │   └── BlacklistRuleTest.java
│               │   ├── service/
│               │   │   ├── FraudDetectionServiceTest.java
│               │   │   └── IdempotencyServiceTest.java
│               │   ├── kafka/
│               │   │   └── TransactionInitiatedListenerTest.java
│               │   ├── contract/
│               │   │   └── KafkaEventSchemaTest.java
│               │   └── integration/
│               │       ├── FraudDetectionIntegrationTest.java
│               │       └── IdempotencyIntegrationTest.java
│               └── resources/
│                   └── application-test.yml
├── k8s/
│   └── fraud-detection-service/
│       ├── deployment.yaml
│       └── service.yaml
└── docker-compose.yml                    # ADD fraud-detection-service definition
```

**Structure Decision**: Same multi-project Gradle structure established for
`transaction-service` and `authorization-service`. Each microservice is an independent
Gradle subproject under `services/`. No shared code between services; cross-service
communication is exclusively via Kafka events using well-defined schemas.

## Application Configuration

### application.yml (base)

```yaml
spring:
  application:
    name: fraud-detection-service
  datasource:
    url: ${SPRING_DATASOURCE_URL}
    username: ${SPRING_DATASOURCE_USERNAME:root}
    password: ${SPRING_DATASOURCE_PASSWORD:}
    hikari:
      maximum-pool-size: 10
      minimum-idle: 2
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate.dialect: org.hibernate.dialect.PostgreSQLDialect
  kafka:
    bootstrap-servers: ${SPRING_KAFKA_BOOTSTRAP_SERVERS}
    consumer:
      group-id: fraud-detection-service-consumer
      auto-offset-reset: earliest
      enable-auto-commit: false
      isolation-level: read_committed
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "com.creditcard.frauddetection.kafka.event"
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      acks: all
      properties:
        enable.idempotence: true
        retries: 3
  flyway:
    enabled: true
    locations: classpath:db/migration

management:
  server:
    port: 8083
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      probes:
        enabled: true
```

### application-test.yml

```yaml
spring:
  flyway:
    enabled: true
  jpa:
    hibernate:
      ddl-auto: none
```

## Key Implementation Details

### 1. Fraud Rule Interface (Strategy Pattern)

```java
public interface FraudRule {
    Optional<String> evaluate(TransactionInitiatedEvent event);
}
```

Each rule is a Spring `@Component`. `FraudDetectionService` has a `List<FraudRule>` injected
via Spring's collection autowiring. All rules are evaluated; results are collected:

```java
List<String> triggeredRules = rules.stream()
    .map(rule -> safeEvaluate(rule, event))   // wraps in try-catch (FR-011)
    .filter(Optional::isPresent)
    .map(Optional::get)
    .toList();
```

### 2. Rule Configuration Loading

Each rule reads its own `FraudRuleConfig` record from CockroachDB at evaluation time:

```java
// VelocityRule.evaluate()
Optional<FraudRuleConfig> config = ruleConfigRepo.findByRuleType("VELOCITY_EXCEEDED");
if (config.isEmpty() || !config.get().isEnabled()) return Optional.empty();
VelocityParams params = objectMapper.readValue(config.get().getParameters(), VelocityParams.class);
```

Reading from DB on each evaluation satisfies SC-004 (changes effective within 60 s,
no restart required). Lightweight point-lookup by primary key.

### 3. Velocity Rule Logic

```java
// Count transactions in the rolling window (excluding current event)
long count = txRecordRepo.countByCardTokenAndOccurredAtAfter(
    event.getCardToken(),
    Instant.now().minus(params.getWindowMinutes(), MINUTES)
);
if (count >= params.getMaxTransactions()) {
    return Optional.of("VELOCITY_EXCEEDED");
}
```

### 4. Amount Anomaly Rule Logic

```java
// Compute average from history (excluding current event)
Optional<BigDecimal> avg = txRecordRepo.findAverageAmountByCardToken(
    event.getCardToken(),
    Instant.now().minus(params.getLookbackDays(), DAYS)
);
BigDecimal baseline = avg.orElse(params.getAbsoluteCeiling());
if (event.getAmount().compareTo(baseline.multiply(params.getMultiplier())) > 0) {
    return Optional.of("AMOUNT_ANOMALY");
}
```

When card has no history, `avg` is empty → fall back to absolute ceiling as baseline.
If `amount > 1 * ceiling` → trigger. (Multiplier applies to baseline in all cases.)

### 5. Blacklist Rule Logic

```java
Optional<FraudRuleConfig> config = ruleConfigRepo.findByRuleType("BLACKLISTED_CARD");
if (config.isEmpty() || !config.get().isEnabled()) return Optional.empty();
if (blacklistRepo.existsByCardToken(event.getCardToken())) {
    return Optional.of("BLACKLISTED_CARD");
}
```

### 6. Fail-Safe Error Handling (FR-011)

```java
private Optional<String> safeEvaluate(FraudRule rule, TransactionInitiatedEvent event) {
    try {
        return rule.evaluate(event);
    } catch (Exception ex) {
        log.warn("Rule evaluation failed for card token [{}], treating as clean. Error: {}",
                 maskToken(event.getCardToken()), ex.getMessage());
        return Optional.empty();
    }
}
```

### 7. Service Transaction Boundary

```java
@Transactional
public Optional<FraudAlertRaisedEvent> evaluate(TransactionInitiatedEvent event) {
    // 1. Evaluate all rules (DB reads, no writes yet)
    List<String> triggered = rules.stream()
        .map(r -> safeEvaluate(r, event)).filter(Optional::isPresent)
        .map(Optional::get).toList();

    // 2. Always write transaction record (for history)
    txRecordRepo.save(buildTransactionRecord(event));

    // 3. Conditionally persist fraud alert
    if (triggered.isEmpty()) return Optional.empty();
    FraudAlert alert = fraudAlertRepo.save(buildFraudAlert(event, triggered));

    // 4. Return event to publish (Kafka publish happens OUTSIDE transaction)
    return Optional.of(buildAlertRaisedEvent(alert));
}
```

### 8. Listener Flow

```java
public void onMessage(ConsumerRecord<String, TransactionInitiatedEvent> record, Acknowledgment ack) {
    // Validate
    if (record.value() == null || ...) { ack.acknowledge(); return; }

    // Idempotency fast-path
    String key = idempotencyService.buildKey("TransactionInitiated", event.getEventId());
    if (idempotencyService.isDuplicate(key)) { ack.acknowledge(); return; }

    // Evaluate (transactional DB writes)
    Optional<FraudAlertRaisedEvent> alertEvent = fraudDetectionService.evaluate(event);

    // Publish to Kafka AFTER transaction commits
    alertEvent.ifPresent(e -> kafkaTemplate.send("fraud.alert.raised", e.getTransactionId().toString(), e));

    ack.acknowledge();
}
```

### 9. Idempotency Pattern (identical to authorization-service)

`ProcessedEventRepository` uses native `ON CONFLICT DO NOTHING` INSERT within the
`FraudDetectionService.evaluate()` @Transactional method. Idempotency record, transaction
record, and fraud alert (if any) are all committed atomically in the same transaction.

### 10. KafkaConsumerConfig (critical: AUTO_OFFSET_RESET_CONFIG)

```java
props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"); // REQUIRED for test reliability
```

`AckMode.MANUAL_IMMEDIATE` — offset committed only after all processing succeeds.

## Flyway Migration Scripts

### V1__create_fraud_alerts.sql
```sql
CREATE TABLE fraud_alerts (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id      UUID          NOT NULL,
    card_token          VARCHAR(512)  NOT NULL,
    amount              DECIMAL(15,4) NOT NULL,
    currency_code       CHAR(3)       NOT NULL,
    triggered_rules     TEXT          NOT NULL,
    detected_at         TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX idx_fraud_alerts_tx_id ON fraud_alerts(transaction_id);
CREATE INDEX idx_fraud_alerts_card  ON fraud_alerts(card_token);
```

### V2__create_fraud_rule_configs.sql
```sql
CREATE TABLE fraud_rule_configs (
    rule_type   VARCHAR(50) PRIMARY KEY,
    enabled     BOOLEAN     NOT NULL DEFAULT true,
    parameters  TEXT        NOT NULL DEFAULT '{}',
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO fraud_rule_configs (rule_type, enabled, parameters) VALUES
    ('VELOCITY_EXCEEDED', true, '{"maxTransactions": 5, "windowMinutes": 10}'),
    ('AMOUNT_ANOMALY',    true, '{"multiplier": 3.0, "lookbackDays": 30, "absoluteCeiling": 10000.00}'),
    ('BLACKLISTED_CARD',  true, '{}');
```

### V3__create_blacklist_entries.sql
```sql
CREATE TABLE blacklist_entries (
    card_token  VARCHAR(512) PRIMARY KEY,
    reason      VARCHAR(255),
    added_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

### V4__create_transaction_records.sql
```sql
CREATE TABLE transaction_records (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id  UUID          NOT NULL UNIQUE,
    card_token      VARCHAR(512)  NOT NULL,
    amount          DECIMAL(15,4) NOT NULL,
    currency_code   CHAR(3)       NOT NULL,
    occurred_at     TIMESTAMPTZ   NOT NULL
);
CREATE INDEX idx_tx_records_card_time ON transaction_records(card_token, occurred_at);
```

### V5__create_processed_events.sql
```sql
CREATE TABLE processed_events (
    event_key    VARCHAR(255) PRIMARY KEY,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

## Complexity Tracking

No constitution violations. All design choices follow established patterns from
`authorization-service` (idempotency, structured logging, JaCoCo gate, Kafka consumer config).
Fraud rules use the Strategy pattern — one Spring @Component per rule — which is the
simplest extensible design for an open set of independently testable rules.
