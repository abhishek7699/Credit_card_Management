# Tasks: Reporting Service API

**Input**: Design documents from `specs/006-reporting-service-api/`

**Prerequisites**: plan.md ✅ spec.md ✅ research.md ✅ data-model.md ✅ contracts/ ✅ quickstart.md ✅

**Tests**: TDD with 100% JaCoCo instruction + branch coverage is **mandatory** (spec SC-006). Test tasks are included in every phase and must be written **before** their corresponding implementation tasks.

**Organization**: Tasks grouped by user story. Phase 2 (Foundational) must complete before any user story begins.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no shared dependencies)
- **[Story]**: Which user story this task belongs to
- All paths are relative to `services/reporting-service/`

---

## Phase 1: Setup (Project Initialization)

**Purpose**: Gradle module, Spring Boot skeleton, config files, test schema DDL. No implementation logic.

- [X] T001 Create Gradle module structure: add `services/reporting-service/` directory tree matching plan.md (`src/main/java/com/creditcard/reporting/`, `src/main/resources/db/`, `src/test/java/com/creditcard/reporting/` subdirs for config/logging, domain, exception, service, web/dto, integration)
- [X] T002 Create `services/reporting-service/build.gradle` with Spring Boot 3.2.5 (`spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-actuator`, `spring-boot-starter-validation`), PostgreSQL JDBC, `jackson-datatype-jsr310`, `logstash-logback-encoder`, JUnit 5 + Mockito, Testcontainers `postgres:15`, and JaCoCo 0.8.11 gate at 100% instruction + branch (exclude `**/ReportingServiceApplication.class`)
- [X] T003 [P] Create `src/main/resources/application.yml` — server port 8086, management port 8091, `spring.flyway.enabled=false`, `spring.jpa.hibernate.ddl-auto=validate`, actuator endpoints (`health`, `health/liveness`, `health/readiness`, `info`, `metrics`, `prometheus`) exposed on management port
- [X] T004 [P] Create `src/main/resources/application-test.yml` — datasource points to Testcontainers via `spring.datasource.url=${TEST_DB_URL:}`, `spring.jpa.hibernate.ddl-auto=create-drop`, active profile `test`
- [X] T005 [P] Create `src/main/resources/logback-spring.xml` — JSON structured logging via `LogstashEncoder`, register `PanMaskingFilter` as a Logback `TurboFilter` on the root appender; include `<springProfile name="!test">` guard so console logging works in tests
- [X] T006 [P] Create `src/main/resources/db/test-schema.sql` — DDL to create `settlement_transactions`, `merchant_settlements`, and `settlement_batches` tables matching the columns in `data-model.md` (used only by Testcontainers `@Sql` in integration tests; NOT run in production)
- [X] T007 [P] Create `src/main/java/com/creditcard/reporting/ReportingServiceApplication.java` — standard `@SpringBootApplication` main class
- [X] T008 [P] Create `services/reporting-service/Dockerfile` — multi-stage build: `eclipse-temurin:17-jdk-alpine` builder, `eclipse-temurin:17-jre-alpine` runtime, expose port 8086, ENTRYPOINT `java -jar app.jar`
- [X] T009 Add `:services:reporting-service` to root `settings.gradle` `include` list

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: PAN masking filter, exception classes, global exception handler, and shared PagedResponse DTO. All must complete before any user story begins.

**⚠️ CRITICAL**: No user story work can begin until Phase 2 is complete.

### Tests (TDD — write first, run to confirm they fail, then implement)

- [X] T010 [P] Write `src/test/java/com/creditcard/reporting/config/logging/PanMaskingFilterTest.java` — tests: PAN regex in message body → DENY; PAN in format params via `MessageFormatter` → DENY; no PAN in either → NEUTRAL; null message → NEUTRAL; null format → NEUTRAL; null params array → NEUTRAL
- [X] T011 [P] Write `src/test/java/com/creditcard/reporting/exception/ExceptionTest.java` — tests: `ResourceNotFoundException(String)` stores message; `InvalidDateRangeException(String)` stores message; both extend `RuntimeException`
- [X] T012 Write `src/test/java/com/creditcard/reporting/web/GlobalExceptionHandlerTest.java` — direct unit tests (no Spring context): `ResourceNotFoundException` → 404 `{"error":"..."}` ; `InvalidDateRangeException` → 400 `{"error":"..."}` ; `MethodArgumentNotValidException` → 400 `{"errors":[{"field":...,"message":...}]}` ; `MethodArgumentNotValidException` with null `defaultMessage` → fallback message `"invalid"`; use `BeanPropertyBindingResult` + `FieldError` + `MethodArgumentNotValidException(MethodParameter, BindingResult)`
- [X] T013 [P] Write initial `src/test/java/com/creditcard/reporting/web/dto/DtoTest.java` — covers `PagedResponse<String>` all getters/setters: `content`, `page`, `size`, `totalElements`, `totalPages`

### Implementation

- [X] T014 [P] Create `src/main/java/com/creditcard/reporting/config/logging/PanMaskingFilter.java` — Logback `TurboFilter`; PAN regex `\b\d{13,19}\b`; checks `event.getMessage()` and `MessageFormatter.arrayFormat(format, params).getMessage()` (no null-check on result — `MessageFormatter` never returns null for non-null format); returns `FilterReply.DENY` on match, `FilterReply.NEUTRAL` otherwise
- [X] T015 [P] Create `src/main/java/com/creditcard/reporting/exception/ResourceNotFoundException.java` — `public class ResourceNotFoundException extends RuntimeException { public ResourceNotFoundException(String message) { super(message); } }`
- [X] T016 [P] Create `src/main/java/com/creditcard/reporting/exception/InvalidDateRangeException.java` — same pattern as `ResourceNotFoundException`
- [X] T017 Create `src/main/java/com/creditcard/reporting/web/GlobalExceptionHandler.java` — `@RestControllerAdvice`; handle `ResourceNotFoundException` → 404; `InvalidDateRangeException` → 400; `MethodArgumentNotValidException` → 400 with list of `{field, message}` pairs (null `defaultMessage` falls back to `"invalid"`)
- [X] T018 [P] Create `src/main/java/com/creditcard/reporting/web/dto/PagedResponse.java` — generic record/class with fields: `List<T> content`, `int page`, `int size`, `long totalElements`, `int totalPages`

**Checkpoint**: Foundation complete — `./gradlew :services:reporting-service:test` should pass with only the foundational tests.

---

## Phase 3: User Story 1 — Transaction History (Priority: P1) 🎯 MVP

**Goal**: `GET /internal/reporting/transactions` with optional filters (cardToken, merchantId, from, to, status), pagination, 90-day max range enforcement, and `PagedResponse<TransactionResponse>` result.

**Independent Test**: Insert 25 transactions; query with individual and combined filters; verify counts, field values, pagination totals, empty results, and 90-day 400 error — all without any US2 or US3 components.

### Tests for User Story 1 (TDD — write first)

- [X] T019 [P] [US1] Write `src/test/java/com/creditcard/reporting/domain/SettlementTransactionTest.java` — tests all getters/setters for every field in `SettlementTransaction` (`transactionId`, `cardToken`, `maskedCardNumber`, `merchantId`, `amount`, `currencyCode`, `status`, `batchId`, `settlementFee`, `netAmount`, `authorizedAt`, `settledAt`, `createdAt`); verify `@Entity` and `@Table(name="settlement_transactions")` annotations present via reflection
- [X] T020 [P] [US1] Write `src/test/java/com/creditcard/reporting/service/ReportingServiceTest.java` — Mockito unit tests for `getTransactions()`: null cardToken → repository called without cardToken predicate; null merchantId → repository called without merchantId predicate; `from` and `to` spanning > 90 days → `InvalidDateRangeException` thrown; all filters null → uses default date window; result → mapped correctly to `PagedResponse<TransactionResponse>`
- [X] T021 [P] [US1] Write `src/test/java/com/creditcard/reporting/web/ReportingControllerTest.java` — `@WebMvcTest(controllers = {ReportingController.class, GlobalExceptionHandler.class})`; tests: `GET /internal/reporting/transactions` → 200 with paged JSON body; invalid `status` query param → 400 with `errors` list; `POST /internal/reporting/transactions` → 405 Method Not Allowed; service throws `InvalidDateRangeException` → 400 propagated through handler
- [X] T022 [P] [US1] Expand `src/test/java/com/creditcard/reporting/web/dto/DtoTest.java` — add `TransactionResponse` getter/setter tests covering all 12 fields (`transactionId`, `cardToken`, `maskedCardNumber`, `merchantId`, `amount`, `currencyCode`, `status`, `batchId`, `settlementFee`, `netAmount`, `authorizedAt`, `settledAt`)

### Implementation for User Story 1

- [X] T023 [P] [US1] Create `src/main/java/com/creditcard/reporting/domain/SettlementTransaction.java` — read-only `@Entity`, `@Table(name="settlement_transactions")`, `@Immutable` (Hibernate), all columns from `data-model.md` mapped with `@Column`; `@Id String transactionId`; `Instant authorizedAt`, `settledAt`, `createdAt`; `BigDecimal amount`, `settlementFee`, `netAmount`; no `@PrePersist`, no write operations
- [X] T024 [P] [US1] Create `src/main/java/com/creditcard/reporting/web/dto/TransactionResponse.java` — plain Java class (or record) with all 12 fields; `BigDecimal` for amounts; `Instant` for timestamps; `@JsonFormat(shape=JsonFormat.Shape.STRING)` on Instants for ISO-8601 serialization
- [X] T025 [US1] Create `src/main/java/com/creditcard/reporting/repository/TransactionReportRepository.java` — `interface TransactionReportRepository extends JpaRepository<SettlementTransaction, String>, JpaSpecificationExecutor<SettlementTransaction>`; no custom methods needed (Specifications handle filtering)
- [X] T026 [US1] Implement `getTransactions(String cardToken, String merchantId, Instant from, Instant to, String status, Pageable pageable)` in `src/main/java/com/creditcard/reporting/service/ReportingService.java` — apply 90-day guard before query (`ChronoUnit.DAYS.between(from, to) > 90` → throw `InvalidDateRangeException`); compose `Specification<SettlementTransaction>` predicates via `Specification.and()`; call `repository.findAll(spec, pageable)`; map `Page<SettlementTransaction>` → `PagedResponse<TransactionResponse>`; default `from` = now minus 30 days if null; default `to` = now if null
- [X] T027 [US1] Implement `GET /internal/reporting/transactions` in `src/main/java/com/creditcard/reporting/web/ReportingController.java` — `@RestController`, `@RequestMapping("/internal/reporting")`; `@GetMapping("/transactions")` accepts `@RequestParam(required=false)` for `cardToken`, `merchantId`, `from` (Instant), `to` (Instant), `status`; `@RequestParam(defaultValue="0") @Min(0) int page`, `@RequestParam(defaultValue="20") @Min(1) @Max(100) int size`; constructs `PageRequest.of(page, size, Sort.by("authorizedAt").descending())`; delegates to `reportingService.getTransactions()`; returns `ResponseEntity<PagedResponse<TransactionResponse>>`
- [X] T028 [US1] Write US1 integration test scenarios 1.1–1.8 in `src/test/java/com/creditcard/reporting/integration/ReportingIntegrationTest.java` — `@SpringBootTest(webEnvironment=RANDOM_PORT)` + `@AutoConfigureTestDatabase(replace=NONE)` + `@Testcontainers` + `@Sql(scripts="/db/test-schema.sql", executionPhase=BEFORE_TEST_CLASS)`; use `TestRestTemplate`; cover: filter by cardToken (1.1), filter by merchantId (1.2), filter by date range (1.3), filter by status (1.4), combined filters (1.5), 90-day exceeded → 400 (1.6), empty result → 200 empty list (1.7), pagination page=1 of 25 (1.8)

**Checkpoint**: `GET /internal/reporting/transactions` fully functional. Run `./gradlew :services:reporting-service:test` — all US1 tests and integration scenarios pass.

---

## Phase 4: User Story 2 — Merchant Summary (Priority: P2)

**Goal**: `GET /internal/reporting/merchants/{merchantId}/summary`, `GET /internal/reporting/merchants/summary`, and `GET /internal/reporting/settlements/{batchId}/merchants/{merchantId}` — aggregated merchant totals from the `merchant_settlements` table.

**Independent Test**: Insert known `merchant_settlements` records for 3 merchants; verify per-merchant summary aggregates (sum of transactionCount, grossAmount, fees, net), all-merchants list, zero-summary for unknown merchant, and per-batch-merchant detail.

### Tests for User Story 2 (TDD — write first)

- [X] T029 [P] [US2] Write `src/test/java/com/creditcard/reporting/domain/MerchantSettlementTest.java` — tests all getters/setters for every field (`id`, `batchId`, `merchantId`, `transactionCount`, `grossAmount`, `totalInterchangeFee`, `netSettlementAmount`, `currencyCode`, `settlementDate`, `createdAt`); verify `@Entity` and `@Table(name="merchant_settlements")` annotations
- [X] T030 [P] [US2] Expand `src/test/java/com/creditcard/reporting/web/dto/DtoTest.java` — add `MerchantSummaryResponse` getter/setter tests (all 8 fields: `merchantId`, `transactionCount`, `grossAmount`, `totalInterchangeFee`, `netSettlementAmount`, `currencyCode`, `batchCount`, `from`, `to`) and `MerchantSettlementResponse` getter/setter tests (all 9 fields)

### Implementation for User Story 2

- [X] T031 [P] [US2] Create `src/main/java/com/creditcard/reporting/domain/MerchantSettlement.java` — read-only `@Entity`, `@Table(name="merchant_settlements")`, `@Immutable`; `@Id UUID id`; `String batchId`, `merchantId`, `currencyCode`; `int transactionCount`; `BigDecimal grossAmount`, `totalInterchangeFee`, `netSettlementAmount`; `LocalDate settlementDate`; `Instant createdAt`
- [X] T032 [P] [US2] Create `src/main/java/com/creditcard/reporting/web/dto/MerchantSummaryResponse.java` — fields: `String merchantId`, `long transactionCount`, `BigDecimal grossAmount`, `BigDecimal totalInterchangeFee`, `BigDecimal netSettlementAmount`, `String currencyCode`, `long batchCount`, `LocalDate from`, `LocalDate to`
- [X] T033 [P] [US2] Create `src/main/java/com/creditcard/reporting/web/dto/MerchantSettlementResponse.java` — fields: `UUID id`, `String batchId`, `String merchantId`, `int transactionCount`, `BigDecimal grossAmount`, `BigDecimal totalInterchangeFee`, `BigDecimal netSettlementAmount`, `String currencyCode`, `LocalDate settlementDate`
- [X] T034 [US2] Create `src/main/java/com/creditcard/reporting/repository/MerchantSettlementReportRepository.java` — `interface MerchantSettlementReportRepository extends JpaRepository<MerchantSettlement, UUID>`; query methods: `findByMerchantIdAndSettlementDateBetween(String merchantId, LocalDate from, LocalDate to)`, `findBySettlementDateBetween(LocalDate from, LocalDate to)`, `findByBatchIdAndMerchantId(String batchId, String merchantId)` returning `Optional<MerchantSettlement>`
- [X] T035 [US2] Expand `src/main/java/com/creditcard/reporting/service/ReportingService.java` with: `getMerchantSummary(String merchantId, LocalDate from, LocalDate to)` — fetch all `MerchantSettlement` for merchant+date range, aggregate in-service (sum transactionCount, grossAmount, fees, net; count batches); if empty list return zero-value `MerchantSummaryResponse` (not 404); `getAllMerchantsSummary(LocalDate from, LocalDate to, Pageable pageable)` — fetch all settlements in date range, group by merchantId, build per-merchant summaries, sort by grossAmount descending, return as `PagedResponse<MerchantSummaryResponse>`; `getMerchantSettlement(String batchId, String merchantId)` — `findByBatchIdAndMerchantId` or throw `ResourceNotFoundException`
- [X] T036 [US2] Expand `src/test/java/com/creditcard/reporting/service/ReportingServiceTest.java` — add Mockito tests: `getMerchantSummary()` with empty list → zero-value response (not exception); `getMerchantSummary()` with 2 records → correct summed totals; `getAllMerchantsSummary()` with 3 merchants → 3-entry paged response; `getMerchantSettlement()` not found → throws `ResourceNotFoundException`
- [X] T037 [US2] Expand `src/main/java/com/creditcard/reporting/web/ReportingController.java` — add: `@GetMapping("/merchants/{merchantId}/summary")` (optional `from`/`to` LocalDate params, default 30 days ago / today); `@GetMapping("/merchants/summary")` (optional `from`/`to`, `page`, `size`); `@GetMapping("/settlements/{batchId}/merchants/{merchantId}")` (path vars only); all delegate to `reportingService`
- [X] T038 [US2] Write US2 integration test scenarios 2.1–2.3 in `src/test/java/com/creditcard/reporting/integration/ReportingIntegrationTest.java` — scenario 2.1: 2 `merchant_settlements` records → summary aggregates correctly (transactionCount=5, grossAmount=500.00, etc.); 2.2: unknown merchant → 200 with zero totals; 2.3: 3 merchants → all-merchants summary has 3 entries

**Checkpoint**: All merchant summary endpoints functional. Run integration tests — scenarios 2.1–2.3 pass alongside 1.1–1.8.

---

## Phase 5: User Story 3 — Settlement Reports (Priority: P3)

**Goal**: `GET /internal/reporting/settlements/{batchId}` — batch summary from `settlement_batches` table; 404 for unknown batchId.

**Independent Test**: Insert a `settlement_batches` row; query by batchId → 200 with all fields; query unknown batchId → 404.

### Tests for User Story 3 (TDD — write first)

- [X] T039 [P] [US3] Write `src/test/java/com/creditcard/reporting/domain/SettlementBatchTest.java` — tests all getters/setters for every field (`batchId`, `triggeredBy`, `status`, `totalTransactionsProcessed`, `totalGrossAmount`, `totalInterchangeFees`, `totalNetAmount`, `startedAt`, `completedAt`, `failureReason`); verify `@Entity` and `@Table(name="settlement_batches")` annotations
- [X] T040 [P] [US3] Expand `src/test/java/com/creditcard/reporting/web/dto/DtoTest.java` — add `SettlementBatchResponse` getter/setter tests covering all 10 fields including nullable `completedAt` and `failureReason`

### Implementation for User Story 3

- [X] T041 [P] [US3] Create `src/main/java/com/creditcard/reporting/domain/SettlementBatch.java` — read-only `@Entity`, `@Table(name="settlement_batches")`, `@Immutable`; `@Id String batchId`; `String triggeredBy`, `status`, `failureReason`; `int totalTransactionsProcessed`; `BigDecimal totalGrossAmount`, `totalInterchangeFees`, `totalNetAmount`; `Instant startedAt`, `completedAt` (nullable)
- [X] T042 [P] [US3] Create `src/main/java/com/creditcard/reporting/web/dto/SettlementBatchResponse.java` — fields match `SettlementBatch` 1:1: `batchId`, `status`, `triggeredBy`, `totalTransactionsProcessed`, `totalGrossAmount`, `totalInterchangeFees`, `totalNetAmount`, `startedAt`, `completedAt`, `failureReason`; `@JsonInclude(JsonInclude.Include.NON_NULL)` on nullable fields
- [X] T043 [US3] Create `src/main/java/com/creditcard/reporting/repository/SettlementBatchReportRepository.java` — `interface SettlementBatchReportRepository extends JpaRepository<SettlementBatch, String>`; no custom methods needed (`findById` from `JpaRepository` is sufficient)
- [X] T044 [US3] Expand `src/main/java/com/creditcard/reporting/service/ReportingService.java` with `getBatch(String batchId)` — calls `settlementBatchRepository.findById(batchId).orElseThrow(() -> new ResourceNotFoundException("Settlement batch not found: " + batchId))`; maps to `SettlementBatchResponse`
- [X] T045 [US3] Expand `src/test/java/com/creditcard/reporting/service/ReportingServiceTest.java` — add Mockito tests: `getBatch()` found → returns `SettlementBatchResponse` with all fields; `getBatch()` not found → throws `ResourceNotFoundException` with correct message
- [X] T046 [US3] Expand `src/main/java/com/creditcard/reporting/web/ReportingController.java` — add `@GetMapping("/settlements/{batchId}")` delegating to `reportingService.getBatch(batchId)`; returns `ResponseEntity<SettlementBatchResponse>` (200); `ResourceNotFoundException` propagates to `GlobalExceptionHandler` → 404
- [X] T047 [US3] Write US3 integration test scenarios 3.1–3.4 in `src/test/java/com/creditcard/reporting/integration/ReportingIntegrationTest.java` — scenario 3.1: insert completed batch → GET returns all fields correctly; 3.2: GET /settlements/{batchId}/merchants/{merchantId} returns merchant settlement detail; 3.3: unknown batchId → 404 with `{"error":"Settlement batch not found: ..."}` ; 3.4: known batch + unknown merchant → 404 with `{"error":"No settlement found for merchantId=... in batchId=..."}`

**Checkpoint**: All 5 REST endpoints functional. All integration scenarios 1.1–1.8, 2.1–2.3, 3.1–3.4 pass.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Controller edge cases, JaCoCo gate verification, infrastructure files.

- [X] T048 [P] Expand `src/test/java/com/creditcard/reporting/web/ReportingControllerTest.java` — add: `GET /internal/reporting/settlements/missing` → 404 propagated correctly from service; `GET /internal/reporting/transactions?status=INVALID_STATUS` → 400 with `errors` list; `POST /internal/reporting/transactions` → 405; `GET /internal/reporting/merchants/summary?size=0` → 400 (size must be ≥ 1); also add `GET /internal/reporting/merchants/summary` → 200 paged response
- [X] T049 Run `./gradlew :services:reporting-service:test jacocoTestCoverageVerification` — resolve any missed instructions or branches by adding targeted test cases (use XML report at `build/reports/jacoco/test/jacocoTestReport.xml` to identify misses); 100% instruction + branch coverage required before completion
- [X] T050 Add `reporting-service` to `docker-compose.yml` — service entry with `build: ./services/reporting-service`, `ports: ["8086:8086", "8091:8091"]`, env vars `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `depends_on: [cockroachdb]`
- [X] T051 [P] Create `k8s/reporting-service/deployment.yaml` — 2 replicas; `containerPort: 8086`; liveness probe `GET /actuator/health/liveness` on port 8091; readiness probe `GET /actuator/health/readiness` on port 8091; resource limits `memory: 256Mi`, `cpu: 500m`; requests `memory: 128Mi`, `cpu: 250m`
- [X] T052 [P] Create `k8s/reporting-service/service.yaml` — `ClusterIP`; `port: 8086`, `targetPort: 8086`, `name: http`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately
- **Foundational (Phase 2)**: Depends on Phase 1 completion — **BLOCKS** all user stories
- **US1 (Phase 3)**: Depends on Phase 2; is the MVP and unblocks US2/US3
- **US2 (Phase 4)**: Depends on Phase 2; can start in parallel with US3 after foundation
- **US3 (Phase 5)**: Depends on Phase 2; can start in parallel with US2 after foundation
- **Polish (Phase 6)**: Depends on US1+US2+US3 completion

### User Story Dependencies

- **US1 (P1)**: Can start after Phase 2 — no dependency on US2 or US3
- **US2 (P2)**: Can start after Phase 2 — reads `MerchantSettlement` entity, independent of US1 domain
- **US3 (P3)**: Can start after Phase 2 — reads `SettlementBatch` entity, independent of US1/US2 domain

### Within Each User Story (TDD Order)

1. Write domain test → Create domain entity
2. Write service test → Implement service method
3. Write controller test → Implement controller endpoint
4. Write DTO tests → Create DTO classes
5. Write integration test (after implementation) → Verify end-to-end

### Parallel Opportunities

- T003–T008: All Phase 1 config files — different files, fully parallel
- T010–T013: Foundational tests — all write to different files, fully parallel
- T014–T016, T018: Foundational implementations — different files, fully parallel
- T019–T024: US1 tests + domain entities + DTOs — different files, fully parallel
- T029–T033: US2 tests + domain + DTOs — different files, fully parallel
- T039–T042: US3 tests + domain + DTO — different files, fully parallel
- T051–T052: k8s manifests — different files, fully parallel

---

## Parallel Example: User Story 1

```bash
# Launch in parallel (T019-T024 — all different files):
Task T019: Write SettlementTransactionTest.java
Task T020: Write ReportingServiceTest.java (US1 methods)
Task T021: Write ReportingControllerTest.java (US1 endpoint)
Task T022: Expand DtoTest.java with TransactionResponse tests
Task T023: Create SettlementTransaction.java entity
Task T024: Create TransactionResponse.java DTO

# Then sequential (depend on T023, T024, T025):
Task T025: Create TransactionReportRepository.java
Task T026: Implement getTransactions() in ReportingService.java
Task T027: Implement GET /transactions in ReportingController.java
Task T028: Write integration test scenarios 1.1–1.8
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001–T009)
2. Complete Phase 2: Foundational (T010–T018)
3. Complete Phase 3: US1 Transaction History (T019–T028)
4. **STOP and VALIDATE**: `GET /internal/reporting/transactions` works end-to-end
5. Run `./gradlew :services:reporting-service:test` — all tests pass

### Incremental Delivery

1. Setup + Foundational → Foundation ready (T001–T018)
2. US1 → `GET /transactions` filterable and paginated (T019–T028) → **MVP deliverable**
3. US2 → merchant summary endpoints (T029–T038) → deploy/demo
4. US3 → settlement batch endpoint (T039–T047) → deploy/demo
5. Polish → 100% JaCoCo gate + infra files (T048–T052)

---

## Notes

- **TDD is mandatory**: every implementation task has a preceding test task; the LLM MUST write tests before implementation in each story
- `PanMaskingFilter`: use `Optional.ofNullable()` pattern to avoid uncoverable null-check branches in JaCoCo (learned from settlement-service)
- `GlobalExceptionHandler`: must be included in `@WebMvcTest(controllers = {...})` controllers list for `@WebMvcTest` to wire it up
- `@MockitoSettings(strictness = Strictness.LENIENT)` may be needed on `ReportingServiceTest` if `@BeforeEach` stubs are used across tests with different call patterns
- All `BigDecimal` fields: use `DECIMAL(15,2)` in DDL; never use `double` or `float`
- `ReportingServiceApplication` is excluded from JaCoCo gate — do not add logic to it
- Commit after each task phase to maintain a clean git history
