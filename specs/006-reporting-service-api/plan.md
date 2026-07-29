# Implementation Plan: Reporting Service API

**Branch**: `006-reporting-service-api` | **Date**: 2026-06-15 | **Spec**: [spec.md](spec.md)

**Input**: Feature specification from `specs/006-reporting-service-api/spec.md`

## Summary

Build a read-only REST reporting microservice (`reporting-service`) that exposes paginated, filterable queries over transaction history, merchant settlement summaries, and settlement batch data. The service reads directly from the settlement-service's CockroachDB tables using a dedicated read-only role, with no writes, no Kafka, and no state changes. Enforces a 90-day maximum date range on all filtered queries. TDD with 100% JaCoCo instruction + branch coverage is mandatory.

## Technical Context

**Language/Version**: Java 17 (LTS)

**Primary Dependencies**:
- Spring Boot 3.2.5 (`spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-actuator`, `spring-boot-starter-validation`)
- Jackson JSR-310 (`jackson-datatype-jsr310`) for Instant/LocalDate serialization
- PostgreSQL JDBC driver (CockroachDB uses PostgreSQL wire protocol)
- Logstash Logback encoder for structured JSON logging
- JaCoCo 0.8.11 (coverage gate)
- **No Spring Batch**, **No Spring Kafka**, **No Flyway** (read-only; tables owned by settlement-service)

**Storage**: CockroachDB — read-only access to `settlement_transactions`, `merchant_settlements`, `settlement_batches` (owned by settlement-service). No migrations in production. Test schema created via `@Sql` scripts in Testcontainers PostgreSQL.

**Testing**:
- Unit: JUnit 5 + Mockito (`@ExtendWith(MockitoExtension.class)`)
- Web: `@WebMvcTest` + `MockMvc` for controller tests
- Integration: `@SpringBootTest(webEnvironment=RANDOM_PORT)` + Testcontainers `postgres:15` + `TestRestTemplate`
- Coverage: JaCoCo 0.8.11, 100% instruction + branch, `ReportingServiceApplication` excluded

**Target Platform**: Linux server (Docker Compose locally, GKE in production)

**Project Type**: Internal REST web-service

**Performance Goals**: P95 < 200ms for filtered queries within 30-day window; P99 < 1s for 90-day range queries

**Constraints**:
- Read-only: no writes, no state mutations
- No Kafka
- 90-day maximum date range on date-filtered queries
- Page size: default 20, max 100
- PAN/raw card data MUST NEVER appear in responses, logs, or errors
- 100% JaCoCo instruction + branch coverage

**Scale/Scope**: Internal consumers (ops tooling, finance dashboards); target 500 concurrent reads (per SC-003)

**Ports**:
- Application: `8086`
- Management (Actuator): `8091`

## Constitution Check

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Tokenization & Zero Raw Card Data | ✅ PASS | Service only exposes `card_token` and `masked_card_number`; PanMaskingFilter required in logback config |
| II. Test-First / 100% Coverage | ✅ PASS | Mandatory; JaCoCo gate configured; TDD approach enforced |
| III. Idempotent Event Consumers | ✅ N/A | No Kafka consumers |
| IV. Iterative One-Step Development | ✅ PASS | One class at a time, test-first |
| V. Layered Architecture & Communication | ⚠️ JUSTIFIED DEVIATION | See Complexity Tracking: cross-schema read access justified for pure read-only analytics |
| VI. Observability & Audit Trail | ✅ PASS | JSON structured logging; Micrometer tracing; PanMaskingFilter; Actuator on port 8091 |
| VII. Proto-First Service Contracts | ✅ N/A | No inter-service gRPC calls; service is accessed by api-gateway via REST (same pattern as other internal services) |
| VIII. Frontend Code Sharing | ✅ N/A | Backend service only |

## Project Structure

### Documentation (this feature)

```text
specs/006-reporting-service-api/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/
│   └── api.md           # REST API contract
├── checklists/
│   └── requirements.md  # Spec quality checklist
└── tasks.md             # Phase 2 output (/speckit-tasks command)
```

### Source Code

```text
services/reporting-service/
├── src/
│   ├── main/
│   │   ├── java/com/creditcard/reporting/
│   │   │   ├── ReportingServiceApplication.java
│   │   │   ├── config/
│   │   │   │   └── logging/
│   │   │   │       └── PanMaskingFilter.java
│   │   │   ├── domain/
│   │   │   │   ├── SettlementTransaction.java   (read-only @Entity)
│   │   │   │   ├── MerchantSettlement.java      (read-only @Entity)
│   │   │   │   └── SettlementBatch.java         (read-only @Entity)
│   │   │   ├── exception/
│   │   │   │   ├── ResourceNotFoundException.java
│   │   │   │   └── InvalidDateRangeException.java
│   │   │   ├── repository/
│   │   │   │   ├── TransactionReportRepository.java     (JpaSpecificationExecutor)
│   │   │   │   ├── MerchantSettlementReportRepository.java
│   │   │   │   └── SettlementBatchReportRepository.java
│   │   │   ├── service/
│   │   │   │   └── ReportingService.java
│   │   │   └── web/
│   │   │       ├── ReportingController.java
│   │   │       ├── GlobalExceptionHandler.java
│   │   │       └── dto/
│   │   │           ├── TransactionResponse.java
│   │   │           ├── PagedResponse.java
│   │   │           ├── MerchantSummaryResponse.java
│   │   │           ├── SettlementBatchResponse.java
│   │   │           └── MerchantSettlementResponse.java
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── application-test.yml
│   │       ├── logback-spring.xml
│   │       └── db/
│   │           └── test-schema.sql   (DDL for Testcontainers; not run in production)
│   └── test/
│       └── java/com/creditcard/reporting/
│           ├── config/logging/
│           │   └── PanMaskingFilterTest.java
│           ├── domain/
│           │   ├── SettlementTransactionTest.java
│           │   ├── MerchantSettlementTest.java
│           │   └── SettlementBatchTest.java
│           ├── exception/
│           │   └── ExceptionTest.java
│           ├── service/
│           │   └── ReportingServiceTest.java     (Mockito)
│           ├── web/
│           │   ├── ReportingControllerTest.java  (@WebMvcTest)
│           │   ├── GlobalExceptionHandlerTest.java
│           │   └── dto/
│           │       └── DtoTest.java
│           └── integration/
│               └── ReportingIntegrationTest.java  (@SpringBootTest + Testcontainers)
├── build.gradle
└── Dockerfile
```

**Structure Decision**: Single Spring Boot service (Option 1 variant). Follows identical package conventions to `settlement-service` (`config/`, `domain/`, `exception/`, `repository/`, `service/`, `web/`). No batch, no Kafka, no scheduler packages. `resources/db/test-schema.sql` contains the DDL needed for Testcontainers integration tests only — not run in production.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| Cross-schema read access (Principle V) | Reporting-service reads `settlement_transactions`, `merchant_settlements`, `settlement_batches` owned by settlement-service | ETL/event-sourcing adds Kafka consumer, synchronization lag, schema duplication, and operational complexity for zero additional business value; reporting is a pure CQRS read-side use case. Access is read-only; a dedicated DB role prevents accidental writes. This is a bounded, documented exception. |
