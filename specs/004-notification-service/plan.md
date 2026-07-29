# Implementation Plan: Notification Service

**Branch**: `004-notification-service` | **Date**: 2026-06-12 | **Spec**: specs/004-notification-service/spec.md

**Input**: Feature specification from `/specs/004-notification-service/spec.md`

---

## Summary

The notification-service consumes two Kafka topics — `transaction.status.updated` (from authorization-service) and `fraud.alert.raised` (from fraud-detection-service) — and delivers email and SMS notifications to cardholders. It exposes a REST management API for registering and updating cardholder contact preferences. All channels are abstracted behind a `NotificationChannelGateway` interface (stub implementations in this version). Idempotency is enforced via a `processed_events` table with `ON CONFLICT DO NOTHING` semantics.

---

## Technical Context

**Language/Version**: Java 17

**Primary Dependencies**:
- Spring Boot 3.2.5 (`spring-boot-starter-web` — required for REST management API)
- `spring-kafka` 3.1.x
- `spring-boot-starter-data-jpa`
- `spring-boot-starter-validation`
- `flyway-core`
- `postgresql` JDBC driver
- `jackson-databind` + `jackson-datatype-jsr310` (auto-configured via `spring-boot-starter-web`)
- `lombok`
- Test: `spring-kafka-test` (`@EmbeddedKafka`), `testcontainers` (`postgresql`)

**Storage**: CockroachDB (PostgreSQL-compatible) — 3 tables: `cardholder_contacts`, `notification_records`, `processed_events`

**Testing**: JUnit 5 + Spring Boot Test + `@EmbeddedKafka` + Testcontainers `PostgreSQLContainer`. JaCoCo 0.8.11, 100% instruction + branch coverage gate.

**Target Platform**: Linux server (containerized), management port 8084, app port 8080

**Project Type**: Spring Boot microservice (Kafka consumer + REST API)

**Performance Goals**: Sub-second notification dispatch after event receipt; REST API p95 < 200ms

**Constraints**:
- No raw PAN ever stored, logged, or transmitted — only opaque `cardToken` from vault and display-safe `maskedCardNumber`
- `transactionNotificationsEnabled = false` suppresses transaction events; fraud alerts are always delivered
- Idempotency: same `eventId` processed at most once per event type
- PCI-DSS: no card data in logs; `recipient_masked` stored in `notification_records` (not raw email/phone in audit table)

**Scale/Scope**: Per-card-token cardholder contact preferences; one notification record per channel per triggering event

---

## Constitution Check

| Principle | Status | Notes |
|-----------|--------|-------|
| No raw card data stored | PASS | `cardToken` is opaque vault token; `maskedCardNumber` is display-safe; raw PAN never accepted |
| No raw card data in logs | PASS | `PanMaskingFilter` pattern inherited; `recipient_masked` in audit records |
| Idempotency on all Kafka consumers | PASS | `processed_events` table with `ON CONFLICT DO NOTHING` on both consumers |
| TDD with 100% JaCoCo coverage | PASS | Unit + integration tests required for all paths |
| Manual Kafka offset acknowledgement | PASS | `AckMode.MANUAL_IMMEDIATE`; ack in `finally` block |
| Atomic DB writes | PASS | `ProcessedEvent` + `NotificationRecord`(s) inserted in single `@Transactional` |
| PCI-DSS awareness | PASS | Audit fields masked; no sensitive data in error messages |
| Spring Boot 3.2.5 / Java 17 | PASS | Consistent with auth-service and fraud-detection-service |

---

## Project Structure

### Documentation (this feature)

```text
specs/004-notification-service/
├── plan.md                                    # This file
├── research.md                                # Phase 0 output
├── data-model.md                              # Phase 1 output
├── quickstart.md                              # Phase 1 output
├── contracts/
│   ├── kafka-consumer-tx-status.md            # transaction.status.updated consumer
│   ├── kafka-consumer-fraud-alert.md          # fraud.alert.raised consumer
│   └── rest-cardholder-contacts.md            # REST management API
└── tasks.md                                   # Phase 2 output (/speckit-tasks)
```

### Source Code

```text
services/notification-service/
├── build.gradle
├── Dockerfile
└── src/
    ├── main/
    │   ├── java/com/creditcard/notification/
    │   │   ├── NotificationServiceApplication.java
    │   │   ├── config/
    │   │   │   ├── KafkaConsumerConfig.java          # Two ConcurrentKafkaListenerContainerFactory beans
    │   │   │   └── logging/
    │   │   │       └── PanMaskingFilter.java          # Logback TurboFilter — no PAN in logs
    │   │   ├── domain/
    │   │   │   ├── CardholderContact.java             # @Entity — cardholder_contacts
    │   │   │   ├── NotificationRecord.java            # @Entity — notification_records
    │   │   │   └── ProcessedEvent.java                # @Entity — processed_events
    │   │   ├── kafka/
    │   │   │   ├── event/
    │   │   │   │   ├── TransactionStatusEvent.java    # Deserialized from transaction.status.updated
    │   │   │   │   └── FraudAlertEvent.java           # Deserialized from fraud.alert.raised
    │   │   │   ├── TransactionStatusListener.java     # @KafkaListener — transaction.status.updated
    │   │   │   └── FraudAlertListener.java            # @KafkaListener — fraud.alert.raised
    │   │   ├── repository/
    │   │   │   ├── CardholderContactRepository.java   # JpaRepository<CardholderContact, String>
    │   │   │   ├── NotificationRecordRepository.java  # JpaRepository<NotificationRecord, UUID>
    │   │   │   └── ProcessedEventRepository.java      # JpaRepository<ProcessedEvent, String>
    │   │   ├── service/
    │   │   │   ├── NotificationService.java           # Core: idempotency + dispatch orchestration
    │   │   │   └── channel/
    │   │   │       ├── NotificationChannelGateway.java # Interface: send(recipient, subject, body)
    │   │   │       ├── EmailGateway.java               # Stub implementation
    │   │   │       └── SmsGateway.java                 # Stub implementation
    │   │   └── web/
    │   │       ├── CardholderContactController.java   # POST/PUT/GET /api/v1/cardholder-contacts
    │   │       └── dto/
    │   │           ├── CardholderContactRequest.java
    │   │           └── CardholderContactResponse.java
    │   └── resources/
    │       ├── application.yml
    │       ├── application-test.yml
    │       └── db/migration/
    │           ├── V1__create_cardholder_contacts.sql
    │           ├── V2__create_notification_records.sql
    │           └── V3__create_processed_events.sql
    └── test/
        └── java/com/creditcard/notification/
            ├── config/
            │   └── logging/
            │       └── PanMaskingFilterTest.java
            ├── integration/
            │   ├── TestKafkaConfig.java               # EmbeddedKafka producer beans for tests
            │   ├── TransactionNotificationIntegrationTest.java   # US1 scenarios 1.1–1.5
            │   ├── FraudAlertNotificationIntegrationTest.java    # US2 scenarios 2.1–2.3
            │   └── ContactManagementIntegrationTest.java         # US3 scenarios 3.1–3.3
            ├── service/
            │   └── NotificationServiceTest.java       # Unit tests — content formatting, masking
            └── web/
                └── CardholderContactControllerTest.java  # MockMvc — validation, 400/404 responses
```

---

## Key Implementation Details

### Two Kafka Container Factories

`spring.json.use.type.headers: false` (same pattern as fraud-detection-service). Each topic deserializes a different event type, so two separate `ConcurrentKafkaListenerContainerFactory` beans are required:

```java
// KafkaConsumerConfig.java
@Bean(name = "txStatusContainerFactory")
public ConcurrentKafkaListenerContainerFactory<String, TransactionStatusEvent> txStatusContainerFactory(...) { ... }

@Bean(name = "fraudAlertContainerFactory")
public ConcurrentKafkaListenerContainerFactory<String, FraudAlertEvent> fraudAlertContainerFactory(...) { ... }
```

Listeners reference their factory by name:
```java
@KafkaListener(topics = "transaction.status.updated", containerFactory = "txStatusContainerFactory", ...)
@KafkaListener(topics = "fraud.alert.raised", containerFactory = "fraudAlertContainerFactory", ...)
```

### NotificationService Transaction Boundary

`process()` is `@Transactional` (default propagation `REQUIRED`):
1. `INSERT INTO processed_events ... ON CONFLICT DO NOTHING` — returns affected row count
2. If 0 rows → duplicate, return immediately
3. Look up `CardholderContact` by `cardToken`
4. Build `NotificationRecord`(s) with status `PENDING`
5. Save all `NotificationRecord`(s)
6. Transaction commits

Channel sends happen **after** the transaction commits. Status updates use `@Transactional(propagation = REQUIRES_NEW)` in a helper method, keeping the audit record accurate even if one channel send fails.

### NotificationChannelGateway Interface

```java
public interface NotificationChannelGateway {
    NotificationChannel channel();           // EMAIL or SMS
    void send(String recipient, String subject, String body);
}
```

`NotificationService` receives `List<NotificationChannelGateway>` injected by Spring. This allows channels to be added without modifying the service.

### Idempotency Key

- Transaction events: `notif:{eventType}:{eventId}` (e.g., `notif:TransactionAuthorized:f47ac10b-...`)
- Fraud events: `notif:FraudAlertRaised:{eventId}`

### Content Formatting

`{last4}` extracted from `CardholderContact.maskedCardNumber` — last 4 characters before end of string.

`maskEmail()` — format: `j***@example.com` (first char + `***` + `@domain`)
`maskPhone()` — format: `***-***-4567` (last 4 digits preserved)

### Fraud Alert Preference Bypass

Fraud alerts **always** deliver to all registered channels regardless of `transactionNotificationsEnabled`. Check is skipped entirely in `FraudAlertListener`.

### Management Port

`management.server.port: 8084` in `application.yml`. App runs on port 8080.

---

## Flyway Migrations

| Migration | Table | Content |
|-----------|-------|---------|
| `V1__create_cardholder_contacts.sql` | `cardholder_contacts` | PK `card_token VARCHAR(512)`, masked_card_number, email, phone_number, transaction_notifications_enabled BOOLEAN DEFAULT TRUE, timestamps |
| `V2__create_notification_records.sql` | `notification_records` | PK `id UUID`, event_id, event_type, channel, recipient_masked, content TEXT, status VARCHAR(10) DEFAULT 'PENDING', failure_reason, timestamps; indexes on event_id and status |
| `V3__create_processed_events.sql` | `processed_events` | PK `event_key VARCHAR(255)`, processed_at TIMESTAMPTZ DEFAULT NOW() |

---

## Complexity Tracking

No constitution violations — no complexity justification needed.

---

## EmbeddedKafka Test Pattern

Inherit the pattern from fraud-detection-service:

```java
@SpringBootTest
@EmbeddedKafka(partitions = 1, topics = {"transaction.status.updated", "fraud.alert.raised"})
@Testcontainers
@ActiveProfiles("test")
@DirtiesContext
class TransactionNotificationIntegrationTest { ... }
```

`application-test.yml` overrides:
```yaml
spring:
  kafka:
    consumer:
      auto-offset-reset: earliest
      bootstrap-servers: ${spring.embedded.kafka.brokers}
    producer:
      bootstrap-servers: ${spring.embedded.kafka.brokers}
```

`AUTO_OFFSET_RESET_CONFIG = "earliest"` must also be set programmatically in `KafkaConsumerConfig` for `@EmbeddedKafka` reliability (same fix as fraud-detection-service).
