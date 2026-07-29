# Implementation Plan: Transaction Service

**Branch**: `001-transaction-service` | **Date**: 2026-06-11 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-transaction-service/spec.md`

## Summary

The `transaction-service` is the entry point for all card payment flows in the Credit Card
Payment Gateway. It accepts payment requests from the `api-gateway` via gRPC, immediately
delegates card number tokenization to HashiCorp Vault Transit Secrets Engine (receiving an
opaque ciphertext token), persists a PENDING transaction record containing only the token
and masked card number to CockroachDB Serverless, publishes a `TransactionInitiated` Kafka
event to downstream services (`authorization-service`, `fraud-detection-service`), and
returns the transaction ID to the caller. It also consumes Kafka status-update events
(AUTHORIZED, DECLINED, FAILED) from downstream services and updates the transaction status
atomically — with full idempotency on all consumers enforced via a `processed_events` table.

Technical approach: Spring Boot 3.x microservice; gRPC server via `net.devh:grpc-spring-boot-starter`; Spring Vault (`spring-vault-core`) for Vault Transit; Spring Data JPA on CockroachDB Serverless; Spring Kafka for event publishing/consuming; Micrometer Tracing for distributed tracing across gRPC metadata and Kafka headers; Gradle multi-project build with `com.google.protobuf` plugin for proto stub generation; JUnit 5 + Mockito + Testcontainers for 100% coverage.

## Technical Context

**Language/Version**: Java 17 (LTS)

**Primary Dependencies**:
- `org.springframework.boot:spring-boot-starter-web` (3.x)
- `org.springframework.boot:spring-boot-starter-data-jpa`
- `org.springframework.kafka:spring-kafka`
- `org.springframework.vault:spring-vault-core:3.x`
- `net.devh:grpc-spring-boot-starter:3.x` (LogNet — gRPC server for Spring Boot 3.x)
- `io.grpc:grpc-protobuf`, `io.grpc:grpc-stub`
- `com.google.protobuf:protobuf-java`
- `org.postgresql:postgresql` (CockroachDB PostgreSQL wire protocol)
- `io.micrometer:micrometer-tracing-bridge-brave` + `io.zipkin.reporter2:zipkin-reporter-brave`
- `org.testcontainers:postgresql`, `org.testcontainers:kafka` (integration tests)
- `org.junit.jupiter:junit-jupiter-api`, `org.mockito:mockito-core`

**Storage**: CockroachDB Serverless — PostgreSQL-compatible wire protocol; accessed via
Spring Data JPA with `org.postgresql` driver. Two tables: `transactions` and
`processed_events`. Hikari connection pool. Serialization retry required for SQLSTATE 40001.

**Testing**: JUnit 5 + Mockito (unit); `@SpringBootTest` + Testcontainers (integration);
proto-generated stubs via Mockito (contract tests for gRPC interface). JaCoCo enforces
100% line + branch coverage as a Gradle build gate.

**Target Platform**: Docker container → Linux pod on Kubernetes / GKE (production).
Local dev via `docker-compose up` (CockroachDB, Kafka/KRaft, HashiCorp Vault, Redis).

**Project Type**: Microservice — gRPC server + Kafka producer + Kafka consumer

**Performance Goals**: Payment submission acknowledged in <500ms p95 (SC-001). Idempotency
key check (CockroachDB lookup) adds <5ms overhead. Vault Transit encrypt call target <50ms.

**Constraints**:
- 100% line and branch test coverage — JaCoCo gate, GitLab CI/CD fails if not met
- Zero raw card numbers in storage, logs, or Kafka events at any time
- CVV and card expiry MUST be discarded immediately after the Vault encrypt call returns
- CockroachDB transaction serialization errors (SQLSTATE 40001) retried transparently
- Vault unavailability → payment submission rejected immediately (no retry in request path)

**Scale/Scope**: `transaction-service` only — first of 7 services in the platform.
gRPC port: `9090`. Kafka topics produced: `transaction.initiated`.
Kafka topics consumed: `transaction.status.updated`.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Pre-Design | Post-Design | Evidence |
|-----------|-----------|-------------|----------|
| **I. Tokenization & Zero Raw Card Data** | ✅ PASS | ✅ PASS | Vault Transit called immediately on receipt; only `card_token` + `masked_card_number` stored in DB; no PAN in Kafka events; CVV/expiry discarded post-Vault call |
| **II. Test-First / 100% Coverage** | ✅ PASS | ✅ PASS | TDD mandated; JaCoCo 100% gate in `build.gradle`; all tests written and confirmed failing before any implementation class is written |
| **III. Idempotent Event Consumers** | ✅ PASS | ✅ PASS | `processed_events` table keyed by `{tx}:{eventType}:{eventId}`; idempotency check + business state committed in one CockroachDB transaction |
| **IV. Iterative One-Step Development** | ✅ PASS | ✅ PASS | `transaction-service` is Service #1; no other service scaffolded until this is approved and complete; class-by-class, method-by-method |
| **V. Layered Architecture & Communication Tiers** | ✅ PASS | ✅ PASS | gRPC handler → Service layer → Repository; gRPC inbound from `api-gateway`; Kafka outbound (`transaction.initiated`); Kafka inbound (status updates); no plain REST service-to-service |
| **VI. Observability & Audit Trail** | ✅ PASS | ✅ PASS | Structured JSON audit log appender; Micrometer trace ID propagated in gRPC metadata + Kafka headers; masked PAN only in logs (`****-****-****-1234`); audit entries for PENDING/AUTHORIZED/DECLINED/FAILED transitions |
| **VII. Proto-First Service Contracts** | ✅ PASS | ✅ PASS | `proto/v1/transaction_service.proto` is Phase 1 deliverable; approved before implementation; stubs generated by Gradle `com.google.protobuf` plugin; stubs not committed |

**Gate result: ALL 7 PRINCIPLES PASS — Phase 0 research and Phase 1 design authorized.**

## Project Structure

### Documentation (this feature)

```text
specs/001-transaction-service/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   ├── transaction_service.proto   # gRPC contract (Phase 1 output)
│   └── kafka-events.md             # Kafka event schemas (Phase 1 output)
└── tasks.md             # Phase 2 output — created by /speckit-tasks
```

### Source Code (repository root)

Multi-project Gradle build. Each microservice is an independent subproject under `services/`.
Proto files live at repository root under `proto/` (Principle VII).

```text
credit-card-payment-gateway/           (repository root)
├── proto/
│   └── v1/
│       └── transaction_service.proto  # Authoritative proto (Principle VII)
├── services/
│   └── transaction-service/
│       ├── src/
│       │   ├── main/
│       │   │   ├── java/com/creditcard/transaction/
│       │   │   │   ├── TransactionServiceApplication.java
│       │   │   │   ├── api/
│       │   │   │   │   ├── grpc/
│       │   │   │   │   │   └── TransactionGrpcService.java
│       │   │   │   │   └── kafka/
│       │   │   │   │       └── TransactionStatusListener.java
│       │   │   │   ├── service/
│       │   │   │   │   ├── TransactionService.java
│       │   │   │   │   ├── TokenizationService.java
│       │   │   │   │   └── IdempotencyService.java
│       │   │   │   ├── domain/
│       │   │   │   │   ├── Transaction.java
│       │   │   │   │   ├── TransactionStatus.java
│       │   │   │   │   └── ProcessedEvent.java
│       │   │   │   ├── repository/
│       │   │   │   │   ├── TransactionRepository.java
│       │   │   │   │   └── ProcessedEventRepository.java
│       │   │   │   ├── event/
│       │   │   │   │   ├── TransactionInitiatedEvent.java
│       │   │   │   │   └── StatusUpdateEvent.java
│       │   │   │   └── config/
│       │   │   │       ├── KafkaConfig.java
│       │   │   │       ├── VaultConfig.java
│       │   │   │       └── ObservabilityConfig.java
│       │   │   └── resources/
│       │   │       ├── application.yml
│       │   │       └── application-local.yml
│       │   └── test/
│       │       ├── java/com/creditcard/transaction/
│       │       │   ├── api/grpc/
│       │       │   │   └── TransactionGrpcServiceTest.java
│       │       │   ├── service/
│       │       │   │   ├── TransactionServiceTest.java
│       │       │   │   ├── TokenizationServiceTest.java
│       │       │   │   └── IdempotencyServiceTest.java
│       │       │   ├── repository/
│       │       │   │   ├── TransactionRepositoryTest.java
│       │       │   │   └── ProcessedEventRepositoryTest.java
│       │       │   ├── integration/
│       │       │   │   ├── TransactionCreationIntegrationTest.java
│       │       │   │   └── StatusUpdateIntegrationTest.java
│       │       │   └── contract/
│       │       │       └── TransactionGrpcContractTest.java
│       │       └── resources/
│       │           └── application-test.yml
│       ├── build.gradle
│       └── Dockerfile
├── k8s/
│   └── transaction-service/
│       ├── deployment.yaml
│       └── service.yaml
├── docker-compose.yml
├── build.gradle                       (root — multi-project config + shared conventions)
├── settings.gradle
└── CLAUDE.md
```

**Structure Decision**: Multi-project Gradle build under `services/`. The `proto/` directory
at repository root is the single authoritative location for all `.proto` files (Principle VII).
The `transaction-service` is the only active subproject for now (Principle IV — no other
services scaffolded until this one is approved).

## Complexity Tracking

> No constitution violations to justify. All seven principles are satisfied as-is.
