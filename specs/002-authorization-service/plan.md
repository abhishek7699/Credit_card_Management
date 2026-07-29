# Implementation Plan: Authorization Service

**Branch**: `002-authorization-service` | **Date**: 2026-06-11 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/002-authorization-service/spec.md`

## Summary

The `authorization-service` is the second microservice in the Credit Card Payment Gateway.
It consumes `TransactionInitiated` Kafka events, validates the card token against an
internal issuer registry (`card_accounts`), checks available credit, persists an
`AuthorizationRecord`, and publishes a `TransactionAuthorized`, `TransactionDeclined`, or
`TransactionFailed` event to the `transaction.status.updated` topic. All Kafka processing
is idempotent via a `processed_events` table with `ON CONFLICT DO NOTHING` atomic inserts.
Concurrent credit deductions are safe under CockroachDB's serializable isolation with
Spring Retry for SQLSTATE 40001 conflicts.

Technical approach: Spring Boot 3.x Kafka consumer + producer; Spring Data JPA on
CockroachDB Serverless; Flyway migrations; Spring Retry for serialization conflicts;
JUnit 5 + Mockito + Testcontainers for 100% TDD coverage; JaCoCo gate; structured JSON
logging via `logstash-logback-encoder`; Micrometer trace propagation via Kafka headers.
No gRPC server in v1 (Kafka-only inter-service communication).

## Technical Context

**Language/Version**: Java 17 (LTS)

**Primary Dependencies**:
- `org.springframework.boot:spring-boot-starter-data-jpa` (3.2.5)
- `org.springframework.kafka:spring-kafka`
- `org.springframework.retry:spring-retry`
- `org.springframework.boot:spring-boot-starter-aop` (required by spring-retry)
- `org.springframework.boot:spring-boot-starter-actuator`
- `org.flywaydb:flyway-core`
- `org.postgresql:postgresql` (CockroachDB PostgreSQL wire protocol)
- `io.micrometer:micrometer-tracing-bridge-brave`
- `io.zipkin.reporter2:zipkin-reporter-brave`
- `net.logstash.logback:logstash-logback-encoder:7.4`
- `org.testcontainers:postgresql`, `org.testcontainers:kafka`, `org.testcontainers:junit-jupiter` (1.19.7)
- `org.springframework.kafka:spring-kafka-test`
- `org.springframework.boot:spring-boot-starter-test`
- `org.mockito:mockito-core`
- JaCoCo Gradle plugin `0.8.11`

**Storage**: CockroachDB Serverless — PostgreSQL-compatible wire protocol; accessed via
Spring Data JPA with `org.postgresql` driver. Three tables: `card_accounts`,
`authorization_records`, and `processed_events`. Serialization retry required for
SQLSTATE 40001. Hikari connection pool. Optimistic locking (`@Version`) on `card_accounts`.

**Testing**: JUnit 5 + Mockito (unit); `@SpringBootTest` + Testcontainers (integration);
Kafka event schema assertions (contract tests). JaCoCo enforces 100% instruction + branch
coverage as a Gradle build gate.

**Target Platform**: Docker container → Linux pod on Kubernetes / GKE (production).
Local dev via `docker-compose up` (CockroachDB, Kafka/KRaft). No Vault dependency
(card tokens are treated as opaque strings; no decryption needed).

**Project Type**: Microservice — Kafka consumer + Kafka producer (no gRPC server in v1)

**Performance Goals**: Authorization decision completed and published within 300 ms p95
end-to-end (SC-001). 500 authorization decisions/second sustained throughput (SC-006).

**Constraints**:
- 100% line and branch test coverage — JaCoCo gate; CI/CD fails if not met
- Zero raw card numbers in storage, logs, or Kafka events at any time (Principle I)
- CockroachDB serialization errors (SQLSTATE 40001) retried transparently (max 3 attempts)
- Fail-closed: when card registry is unavailable, publish FAILED, never silently approve
- Kafka offset committed only after result event published AND idempotency record committed
- Authorization-service owns its own CockroachDB schema; no cross-service table access

**Scale/Scope**: `authorization-service` only — second of 7 services in the platform.
Management port: `8082`. Kafka topics consumed: `transaction.initiated`.
Kafka topics produced: `transaction.status.updated`.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Pre-Design | Post-Design | Evidence |
|-----------|-----------|-------------|----------|
| **I. Tokenization & Zero Raw Card Data** | ✅ PASS | ✅ PASS | Card token is opaque Vault ciphertext; authorization-service never decrypts it; `card_accounts` stores only the token as an opaque key; no PAN in any DB column, Kafka event, or log message; `PanMaskingFilter` (defense-in-depth) blocks 13–19 digit sequences from logs |
| **II. Test-First / 100% Coverage** | ✅ PASS | ✅ PASS | TDD mandated; JaCoCo 100% instruction + branch gate in `build.gradle`; all tests written and confirmed failing before implementation; unit (Mockito) + integration (Testcontainers) + contract (event schema assertions) |
| **III. Idempotent Event Consumers** | ✅ PASS | ✅ PASS | `processed_events` table keyed by `auth:{eventType}:{eventId}`; atomic `ON CONFLICT DO NOTHING` insert; committed in same DB transaction as credit deduction and `AuthorizationRecord`; Kafka offset committed only after all three succeed |
| **IV. Iterative One-Step Development** | ✅ PASS | ✅ PASS | `authorization-service` is Service #2; `transaction-service` (Service #1) is complete and approved; one class at a time, one method at a time; no stubs for future services |
| **V. Layered Architecture & Communication Tiers** | ✅ PASS | ✅ PASS | API Layer: `TransactionInitiatedListener`; Service Layer: `AuthorizationService`, `IdempotencyService`; Repository Layer: `CardAccountRepository`, `AuthorizationRecordRepository`, `ProcessedEventRepository`; Kafka-only inter-service communication (no gRPC server in v1; no plain REST) |
| **VI. Observability & Audit Trail** | ✅ PASS | ✅ PASS | Structured JSON audit log (`logstash-logback-encoder`); Micrometer `traceId` propagated from inbound Kafka header → outbound Kafka header + MDC; `PanMaskingFilter` prevents raw card data in logs; every authorization decision logged with masked card token, outcome, reason code, and timestamp |
| **VII. Proto-First Service Contracts** | ✅ PASS | ✅ PASS | No gRPC interface in v1 — Principle VII not triggered; Kafka event contracts documented in `contracts/kafka-events.md`; if gRPC is added in a future iteration, a `.proto` file must be defined and approved before implementation begins |
| **VIII. Frontend Code Sharing** | ✅ N/A | ✅ N/A | Backend service; no frontend impact |

**Gate result: ALL 8 PRINCIPLES PASS — Phase 0 research and Phase 1 design authorized.**

## Project Structure

### Documentation (this feature)

```text
specs/002-authorization-service/
├── plan.md              # This file
├── spec.md              # Feature specification
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── kafka-events.md  # Kafka event schemas (Phase 1 output)
└── tasks.md             # Phase 2 output — created by /speckit-tasks
```

### Source Code (repository root)

Multi-project Gradle build. The authorization-service is an independent subproject under
`services/` alongside the transaction-service.

```text
credit-card-payment-gateway/           (repository root)
├── settings.gradle                    # ADD: include ':services:authorization-service'
├── services/
│   ├── transaction-service/           # Service #1 (complete)
│   └── authorization-service/         # Service #2 (this feature)
│       ├── build.gradle
│       ├── Dockerfile
│       └── src/
│           ├── main/
│           │   ├── java/com/creditcard/authorization/
│           │   │   ├── AuthorizationServiceApplication.java
│           │   │   ├── api/
│           │   │   │   └── kafka/
│           │   │   │       └── TransactionInitiatedListener.java
│           │   │   ├── service/
│           │   │   │   ├── AuthorizationService.java
│           │   │   │   └── IdempotencyService.java
│           │   │   ├── domain/
│           │   │   │   ├── CardAccount.java
│           │   │   │   ├── CardAccountStatus.java
│           │   │   │   ├── AuthorizationRecord.java
│           │   │   │   ├── AuthorizationOutcome.java
│           │   │   │   └── ReasonCode.java
│           │   │   ├── repository/
│           │   │   │   ├── CardAccountRepository.java
│           │   │   │   ├── AuthorizationRecordRepository.java
│           │   │   │   └── ProcessedEventRepository.java
│           │   │   ├── event/
│           │   │   │   ├── TransactionInitiatedEvent.java
│           │   │   │   └── AuthorizationResultEvent.java
│           │   │   └── config/
│           │   │       ├── KafkaConsumerConfig.java
│           │   │       ├── KafkaProducerConfig.java
│           │   │       └── logging/
│           │   │           └── PanMaskingFilter.java
│           │   └── resources/
│           │       ├── application.yml
│           │       ├── application-local.yml
│           │       ├── logback-spring.xml
│           │       └── db/
│           │           └── migration/
│           │               ├── V1__create_card_accounts.sql
│           │               ├── V2__create_authorization_records.sql
│           │               └── V3__create_processed_events.sql
│           └── test/
│               └── java/com/creditcard/authorization/
│                   ├── domain/
│                   │   ├── CardAccountTest.java
│                   │   ├── CardAccountStatusTest.java
│                   │   ├── AuthorizationRecordTest.java
│                   │   └── ReasonCodeTest.java
│                   ├── service/
│                   │   ├── AuthorizationServiceTest.java
│                   │   └── IdempotencyServiceTest.java
│                   ├── api/kafka/
│                   │   └── TransactionInitiatedListenerTest.java
│                   ├── contract/
│                   │   └── KafkaEventSchemaTest.java
│                   └── integration/
│                       ├── AuthorizationIntegrationTest.java
│                       └── IdempotencyIntegrationTest.java
├── k8s/
│   └── authorization-service/
│       ├── deployment.yaml
│       └── service.yaml
└── docker-compose.yml                 # ADD authorization-service service definition
```

**Structure Decision**: Same multi-project Gradle structure established for
`transaction-service`. Each microservice is an independent Gradle subproject under
`services/`. No shared code between services (each owns its domain); cross-service
communication is exclusively via Kafka events using well-defined schemas.

## Application Configuration

### application.yml (base)

```yaml
spring:
  application:
    name: authorization-service
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
      group-id: authorization-service-consumer
      auto-offset-reset: earliest
      enable-auto-commit: false
      isolation-level: read_committed
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "com.creditcard.authorization.event"
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
    port: 8082
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
  kafka:
    consumer:
      auto-offset-reset: earliest
  flyway:
    enabled: true
  jpa:
    hibernate:
      ddl-auto: create-drop
```

## Key Implementation Details

### 1. Idempotency Pattern (same as transaction-service)

`ProcessedEventRepository` uses a native `ON CONFLICT DO NOTHING` INSERT:

```java
@Modifying(flushAutomatically = true)
@Query(value = "INSERT INTO processed_events (event_key, processed_at) " +
               "VALUES (:eventKey, now()) ON CONFLICT DO NOTHING",
       nativeQuery = true)
int insertIfAbsent(@Param("eventKey") String eventKey);
```

`IdempotencyService.recordAndExecute(key, runnable)`:
- Calls `insertIfAbsent(key)`
- Returns 0 → duplicate, return early (no-op)
- Returns 1 → new event, execute `runnable`

### 2. Authorization Retry for SQLSTATE 40001

```java
@Retryable(
    retryFor = {OptimisticLockingFailureException.class,
                CannotSerializeTransactionException.class},
    maxAttempts = 3,
    backoff = @Backoff(delay = 50, multiplier = 2, maxDelay = 500)
)
@Transactional
public AuthorizationOutcome authorize(TransactionInitiatedEvent event) { ... }
```

### 3. Authorization Flow (within `@Transactional`)

```java
// 1. Look up card
CardAccount account = cardAccountRepo.findByCardToken(event.getPayload().getCardToken());
if (account == null) → return DECLINED(INVALID_CARD)

// 2. Check status
if (account.getStatus() != CardAccountStatus.ACTIVE) → return DECLINED(CARD_NOT_ACTIVE)

// 3. Check credit
BigDecimal amount = new BigDecimal(event.getPayload().getAmount());
if (amount.compareTo(account.getAvailableCredit()) > 0) → return DECLINED(INSUFFICIENT_CREDIT)

// 4. Deduct and save (optimistic locking via @Version on CardAccount)
account.setAvailableCredit(account.getAvailableCredit().subtract(amount));
cardAccountRepo.save(account);  // throws OptimisticLockingFailureException on version conflict

// 5. Persist authorization record
authorizationRecordRepo.save(new AuthorizationRecord(transactionId, cardToken, amount, AUTHORIZED));

// return AUTHORIZED
```

### 4. Kafka Publish (AFTER transaction commit)

```java
// In TransactionInitiatedListener (outside @Transactional):
AuthorizationOutcome outcome = authorizationService.authorize(event); // @Transactional scope ends here
kafkaTemplate.send("transaction.status.updated", event.getPayload().getTransactionId(),
    buildResultEvent(outcome, event));
ack.acknowledge();  // Only after successful Kafka send
```

### 5. Event Types and Routing

The `eventType` field in the outbound event MUST be one of:
- `"TransactionAuthorized"` → triggers `handleAuthorized` in transaction-service
- `"TransactionDeclined"` → triggers `handleDeclined` in transaction-service
- `"TransactionFailed"` → triggers `handleFailed` in transaction-service

These string values MUST match exactly (transaction-service routes on them by string equality).

## Flyway Migration Scripts

### V1__create_card_accounts.sql
```sql
CREATE TABLE card_accounts (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    card_token       VARCHAR(512)  NOT NULL UNIQUE,
    status           VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    credit_limit     DECIMAL(15,4) NOT NULL,
    available_credit DECIMAL(15,4) NOT NULL,
    currency_code    CHAR(3)       NOT NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version          BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT chk_card_status         CHECK (status IN ('ACTIVE', 'BLOCKED', 'EXPIRED')),
    CONSTRAINT chk_credit_limit_pos    CHECK (credit_limit > 0),
    CONSTRAINT chk_available_credit_nn CHECK (available_credit >= 0)
);
CREATE INDEX idx_card_accounts_token ON card_accounts(card_token);
```

### V2__create_authorization_records.sql
```sql
CREATE TABLE authorization_records (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_id   UUID          NOT NULL,
    card_token       VARCHAR(512)  NOT NULL,
    requested_amount DECIMAL(15,4) NOT NULL,
    currency_code    CHAR(3)       NOT NULL,
    outcome          VARCHAR(20)   NOT NULL,
    reason_code      VARCHAR(50),
    decided_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT chk_outcome CHECK (outcome IN ('AUTHORIZED', 'DECLINED', 'FAILED'))
);
CREATE INDEX idx_auth_records_tx_id ON authorization_records(transaction_id);
```

### V3__create_processed_events.sql
```sql
CREATE TABLE processed_events (
    event_key    VARCHAR(255) PRIMARY KEY,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

## Complexity Tracking

No constitution violations. All design choices follow established patterns from
`transaction-service` (idempotency, retry, structured logging, JaCoCo gate). No additional
complexity justification required.
