# Tasks: Mastercard Gateway Test Environment as Payment Processor

**Input**: plan.md (013-aligned), spec.md, data-model.md, contracts/, research.md, quickstart.md

**Scope of this file**: plan phases 1, 2 and 4 only.
- Phase 1 (US1): refactor, no behavior change.
- Phase 2 (US2): Mastercard processor selected by config, tested against WireMock.
- Phase 4 (US4): settlement capture.
- **Phase 3 (US3, live MTF calls) is excluded.** It stays blocked until the owner has a test merchant account (gates G3a and G3b).

**Checkpoints**: each phase ends with a checkpoint task. Stop there and show the result before starting the next phase.

**Facts checked against the merged code (013 on `origin/main`)**:
- Flyway: authorization-service already has V1 to V4, so the new migration is `V5__processor_columns.sql`. transaction-service has V1 to V4, so the new one is `V5__processor_columns.sql`. settlement-service has V1 to V5 and needs no migration in this scope.
- `transaction.failure_reason` already exists (013 V3). Do not add it again.
- Timer keys live under `transaction.timeouts.*` in transaction-service's `application.yml`. `TimerOrdering` already exists and already checks rules 1 to 3.
- Tests: `TestContainers` and Docker are required for the integration tests. Run them the same way the existing suite does.

**Test rule**: no existing test is edited, except constructor wiring. Any other test edit is a stop signal (plan, Phase 1 step 4).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: different files, no dependency on an incomplete task.
- **[Story]**: US1, US2 or US4 (spec.md user stories). Setup, Foundational and Polish have no story label.

---

## Setup (before plan phase 1): baseline and characterization tests

**Purpose**: record the baseline, and pin current approval behavior before anything moves.

- [ ] T001 Record the baseline. Run `./gradlew check` in `services/authorization-service`, `services/transaction-service`, `services/settlement-service` and `services/booking-service`, then `COMPOSE_PROJECT_NAME=credit_card scripts/e2e-test.sh` from the repo root. Save the summaries to `specs/014-mastercard-mtf-processor/baseline.txt`. Do not change any code in this task.
- [ ] T002 [P] Write the characterization test `services/authorization-service/src/test/java/com/creditcard/authorization/service/AuthorizationCharacterizationTest.java`. It runs against the **current** `AuthorizationService` (no refactor yet) and covers: approve (available credit drops by the amount, record AUTHORIZED, result event AUTHORIZED); decline by insufficient credit (INSUFFICIENT_CREDIT); blocked card (CARD_NOT_ACTIVE); unknown card (INVALID_CARD, no card row written). Assert the exact outcome, reason code, credit delta and record outcome for each.
- [ ] T003 [P] Write `services/authorization-service/src/test/java/com/creditcard/authorization/service/CreditReleaseCharacterizationTest.java`. It runs against the current `CreditReleaseService`. Cover: release restores the credit once; a second release is a no-op; release with no record writes a TIMED_OUT marker; release of a DECLINED record changes nothing.
- [ ] T004 Checkpoint, setup: the new characterization tests pass against the unchanged code. The full baseline from T001 still matches. Proceed to plan phase 1 without stopping (this is part of phase 1's work).

---

## Foundational (part of plan phase 1, blocking for plan phases 2 and 4)

**Purpose**: the interface and value types that every later task uses.

- [ ] T005 [P] Create `services/authorization-service/src/main/java/com/creditcard/authorization/processor/PaymentProcessor.java`. It is an interface with `authorize(AuthorizeRequest)`, `lookup(String transactionId)`, `capture(String transactionId, BigDecimal amount, String currencyCode)`, and `releaseHold(String transactionId, String cardToken)`. Copy the Javadoc from `contracts/payment-processor.md`.
- [ ] T006 [P] Create `services/authorization-service/src/main/java/com/creditcard/authorization/processor/ProcessorDecision.java`. It is a value type with `outcome` (enum `ProcessorOutcome`: APPROVED, DECLINED, UNKNOWN, RELEASED, RELEASE_REJECTED, NO_OP, CAPTURED, CAPTURE_REJECTED), `reasonCode`, and `reference`. Its `toString()` never includes card or session values. Add `ProcessorDecisionTest.java` in the same package.
- [ ] T007 [P] Create `services/authorization-service/src/main/java/com/creditcard/authorization/processor/AuthorizeRequest.java`. Fields: `transactionId`, `amount`, `currencyCode`, `merchantId`, `cardToken` (simulator path), `paymentSessionId` (Mastercard path). Its `toString()` masks `cardToken` and `paymentSessionId`, showing only the last 4 characters. Add `AuthorizeRequestTest.java`.
- [ ] T008 Checkpoint: the three types compile and their tests pass. Nothing calls them yet, so no behavior changes.

---

## Plan phase 1 (US1): Refactor the approval decision behind the interface

**Goal**: move the decision and the release logic behind `PaymentProcessor`, with no behavior change.

**Independent test**: `./gradlew check` in authorization-service passes, and the T002 and T003 characterization tests still pass.

### Tests first (Principle II)

- [ ] T009 [P] [US1] Write `services/authorization-service/src/test/java/com/creditcard/authorization/processor/ReleaseHoldNoOpTest.java`. Cover `SimulatedProcessor.releaseHold` with no authorization record: it returns `NO_OP`, makes no change to `card_accounts.available_credit`, and writes no record.
- [ ] T010 [P] [US1] Write `services/authorization-service/src/test/java/com/creditcard/authorization/processor/SweepVsInFlightAuthorizeTest.java`. Cover: a timeout marker written first makes a later `authorize` return empty and leave credit unchanged.

### Implementation

- [ ] T011 [US1] Create `services/authorization-service/src/main/java/com/creditcard/authorization/processor/SimulatedProcessor.java`. Do **not** annotate it `@Component` (T018 creates the bean, so there is exactly one). Move the decision logic from `AuthorizationService.decide` into `authorize`: missing card → INVALID_CARD; inactive → CARD_NOT_ACTIVE; insufficient credit → INSUFFICIENT_CREDIT; otherwise debit and APPROVED. Move `persistAndReturn` as the `authorization_records` write. `lookup` returns `UNKNOWN`. `capture` returns `CAPTURE_REJECTED`.
- [ ] T012 [US1] In `SimulatedProcessor.releaseHold`, move the balance part of `CreditReleaseService.release`: find the AUTHORIZED record, add its `requestedAmount` back to `available_credit` under the card lock, and set the record to RELEASED. If there is no AUTHORIZED record, return `NO_OP`. This makes T009 pass.
- [ ] T013 [US1] Refactor `services/authorization-service/src/main/java/com/creditcard/authorization/service/AuthorizationService.java`. Keep `@Transactional`, `@Retryable`, the card lock and the timeout-marker check, in that order. Replace the inline decision with `processor.authorize(...)`. Keep the return type (`Optional<AuthorizationResultEvent>`). The constructor takes `PaymentProcessor`.
- [ ] T014 [US1] Refactor `services/authorization-service/src/main/java/com/creditcard/authorization/service/CreditReleaseService.java`. Keep the `processed_events` duplicate check, the card lock and the TIMED_OUT marker (written when `releaseHold` returns `NO_OP` and no record exists). Replace the balance work with `processor.releaseHold(transactionId, cardToken)`. The public method `release(eventId, transactionId, cardToken)` does not change.
- [ ] T015 [US1] Constructor wiring only, in existing tests that build `AuthorizationService` or `CreditReleaseService` directly (`AuthorizationServiceTest`, `CreditReleaseServiceTest`, `AuthorizationListenerIntegrationTest`, `IdempotencyIntegrationTest`). Pass a `SimulatedProcessor` built from the same repositories. Any other edit to these tests is a stop signal.
- [ ] T016 [US1] Make the characterization tests from T002 and T003 pass against the refactored code without editing them. If one fails, fix the production code, not the test.
- [ ] T017 [US1] Run `./gradlew check` in authorization-service. JaCoCo must stay at 100% instruction and branch coverage. Add tests for any new branch, and do not exclude code from coverage.
- [ ] T018 [US1] Create the bean in `services/authorization-service/src/main/java/com/creditcard/authorization/processor/ProcessorConfig.java`. For now it always returns `SimulatedProcessor`. Phase 2 extends it. Add `ProcessorConfigTest.java`.

### Checkpoint

- [ ] T019 [US1] Checkpoint, plan phase 1. Run `./gradlew check` in authorization-service, transaction-service, settlement-service and booking-service. Then run `COMPOSE_PROJECT_NAME=credit_card scripts/e2e-test.sh`. Compare against `baseline.txt` from T001. Every test must pass, and no existing test may be edited beyond T015. Show the per-file summary and stop for approval.

---

## Plan phase 2 (US2): Route approvals to Mastercard Gateway by configuration

**Goal**: `payment.processor=mastercard` selects the Mastercard processor. The simulator stays the default. Tests use WireMock and need no credentials.

**Independent test**: the WireMock scenarios in T027 pass, and the default configuration still behaves as before.

### Setup and research (before tests)

- [ ] T020 [US2] Add WireMock as a test dependency to `services/authorization-service/build.gradle`: `testImplementation 'org.wiremock:wiremock-jre8-standalone:2.35.1'`. It is the same version api-gateway uses.
- [ ] T021 [US2] Confirm the Retrieve transaction path and the duplicate-identifier error code on the Mastercard Version 100 reference page (research R3, R4). Record what you confirmed in `research.md`. Do this before writing the WireMock stubs, so the stubs match the real API.

### Configuration

- [ ] T022 [US2] In `services/authorization-service/src/main/resources/application.yml`, add `payment.processor: ${PAYMENT_PROCESSOR:simulated}`, `mastercard.api-version: 100`, and `mastercard.base-url`, `mastercard.merchant-id`, `mastercard.api-password` from `MASTERCARD_BASE_URL`, `MASTERCARD_MERCHANT_ID`, `MASTERCARD_API_PASSWORD` (empty defaults). Add `mastercard.read-timeout-seconds: ${MASTERCARD_READ_TIMEOUT_SECONDS:10}`.
- [ ] T023 [US2] In `services/transaction-service/src/main/resources/application.yml`, change `transaction.timeouts.mastercard-read-timeout-seconds` from the literal `10` to `${MASTERCARD_READ_TIMEOUT_SECONDS:10}`. This makes M come from one environment variable. Update `TimerOrderingTest` only if it reads this key.
- [ ] T024 [US2] Add `MASTERCARD_READ_TIMEOUT_SECONDS`, `PAYMENT_PROCESSOR` (default `simulated`), and the three `MASTERCARD_*` variables to `docker-compose.yml` for both services. Secrets have no default. Document them in `.env.example`, with no real values.
- [ ] T025 [US2] Create `services/authorization-service/src/main/java/com/creditcard/authorization/processor/MastercardProperties.java` as a record with `apiVersion`, `baseUrl`, `merchantId`, `apiPassword`, and the timeouts. Its `toString()` masks `apiPassword` and `merchantId`. Add `MastercardPropertiesTest.java`, which asserts the password string never appears in `toString()`.

### Flyway (additive, nullable)

- [ ] T026 [P] [US2] Create `services/authorization-service/src/main/resources/db/migration/V5__processor_columns.sql`. Add to `authorization_records`: `processor VARCHAR(16)`, `processor_reference VARCHAR(64)`, `release_status VARCHAR(16)`. Each column is nullable. If one already exists (check V4 first), leave it out of V5.
- [ ] T027 [P] [US2] Create `services/transaction-service/src/main/resources/db/migration/V5__processor_columns.sql`. Add to `transactions`: `decline_reason VARCHAR(64)`, `processor VARCHAR(16)`, `processor_reference VARCHAR(64)`. Do not add `failure_reason`, which exists from V3. Add a migration test in the transaction-service's existing migration test setup.

### Tests first (WireMock)

- [ ] T028 [US2] Write `services/authorization-service/src/test/java/com/creditcard/authorization/processor/MastercardGatewayProcessorTest.java` with WireMock. It must cover these cases (plan phase 2, step 4): approve; decline (the decline reason is recorded); timeout, then lookup says approved; timeout, then lookup says declined; timeout, then lookup unknown (result stays UNKNOWN); duplicate identifier response (lookup is called, and AUTHORIZE is **not** re-sent); malformed body (treated as UNKNOWN, then lookup); VOID approved; VOID rejected because the transaction is captured (`RELEASE_REJECTED`); 5xx (treated as UNKNOWN). Each test verifies the request body, including `order.id` and `transaction.id` equal to our transactionId, and `sourceOfFunds.session.id` equal to the session ID.
- [ ] T029 [P] [US2] Write `services/authorization-service/src/test/java/com/creditcard/authorization/processor/MastercardLogSecretTest.java`. Attach a log appender, run approve, decline and timeout, and assert that no log line contains the session ID in full, the API password, the merchant ID, or a PAN. This test is required by constitution Principle IX.
- [ ] T030 [P] [US2] Write `services/authorization-service/src/test/java/com/creditcard/authorization/processor/ProcessorConfigSelectionTest.java`. Cover: unset or `simulated` → `SimulatedProcessor`; `mastercard` → `MastercardGatewayProcessor`; any other value → startup fails with a clear message.

### Implementation

- [ ] T031 [US2] Create `services/authorization-service/src/main/java/com/creditcard/authorization/processor/MastercardClient.java`. It uses Spring `RestClient` over HTTPS with certificate verification on, an explicit connect timeout and the read timeout from `MastercardProperties`. Authentication is basic auth `merchant.<id>:<password>`, as in `contracts/mastercard-gateway.md`. It sends a `traceparent` header. It logs status and our IDs only, never a body. Add `MastercardClientTest.java`, using WireMock for the timeout case.
- [ ] T032 [US2] Create `services/authorization-service/src/main/java/com/creditcard/authorization/processor/MastercardGatewayProcessor.java`. Implement `authorize`, `lookup` and `releaseHold` using the response mapping table in `contracts/mastercard-gateway.md`. Timeout, connection error, malformed body and 5xx return `UNKNOWN`. A duplicate-identifier response triggers `lookup`, and AUTHORIZE is never re-sent. `capture` throws `UnsupportedOperationException("capture is phase 4")`, and a test covers that line. This makes T028 pass.
- [ ] T033 [US2] Extend `services/authorization-service/src/main/java/com/creditcard/authorization/processor/ProcessorConfig.java` to choose the bean from `payment.processor`. An unknown value fails startup. This makes T030 pass.
- [ ] T034 [US2] In `AuthorizationService`, when `processor.authorize` returns `UNKNOWN`, call `processor.lookup(transactionId)`. If the lookup says APPROVED or DECLINED, use that result. If it is still UNKNOWN, return `Optional.empty()`: no result is published, and the 013 sweep later fails the transaction. Add `AuthorizationUnknownResultTest.java`. Do not change the simulator path, which never returns UNKNOWN.
- [ ] T035 [US2] Write the Flyway checks for the new columns (T026 and T027), and make sure the migration guard in CI accepts V5 (no bare `INT` or `INTEGER`). Run `scripts/check-flyway-int-columns.sh` if it applies.

### Checkpoint

- [ ] T036 [US2] Checkpoint, plan phase 2. Run `./gradlew check` in authorization-service and transaction-service, with 100% JaCoCo. Run the e2e with the default simulator (`PAYMENT_PROCESSOR` unset) and confirm the same results as before. Confirm T029 passes and no secret is in test output. Show the per-file summary and stop for approval.

---

## Plan phase 4 (US4): Capture approved payments at settlement

**Goal**: when `payment.processor=mastercard`, settlement captures each AUTHORIZED payment before it is marked SETTLED. Simulator behavior is unchanged.

**Independent test**: with the WireMock stand-in, every AUTHORIZED row is captured once and then SETTLED. A failed capture leaves the row AUTHORIZED.

**Hook point (013 aligned)**: `SettlementItemProcessor.process` builds the item. `SettlementItemWriter.write` sets SETTLED. AUTHORIZED rows reach settlement only through `transaction.authorized` from transaction-service (013 option Y). Capture goes in the processor, so the writer only runs after a successful capture.

### Research (before tests)

- [ ] T037 [US4] Confirm the Capture request fields for a gateway-initiated authorization on the Version 100 Capture page (research R10). Record the result in `research.md`, before writing the tests.

### Tests first

- [ ] T038 [US4] Write `services/settlement-service/src/test/java/com/creditcard/settlement/batch/processor/SettlementCaptureTest.java` with WireMock. Cover: simulator (`payment.processor=simulated`) makes no gateway call; Mastercard CAPTURED → the item is returned; CAPTURE_REJECTED → the item is filtered (`process` returns null), the row stays AUTHORIZED, `lookup` is called, and the failure is logged with the transactionId and reasonCode; UNKNOWN → same as rejected; a second run after SETTLED throws `AlreadySettledException` and makes no second capture.
- [ ] T039 [P] [US4] Write `services/settlement-service/src/test/java/com/creditcard/settlement/batch/processor/CaptureLogSecretTest.java`. Assert that no log line contains the API password or a PAN during capture.

### Implementation

- [ ] T040 [US4] Create `services/settlement-service/src/main/java/com/creditcard/settlement/capture/MastercardCaptureClient.java`. It is a minimal client: `capture(transactionId, amount, currencyCode)` returns CAPTURED, CAPTURE_REJECTED or UNKNOWN, plus `lookup(transactionId)`. It reads the same `MASTERCARD_*` variables, uses the same timeout, and reuses the mapping from T032. It is a copy, not a shared module, because settlement is a separate deployable (plan, Phase 4 step 3). Owner confirmation of this choice is still open.
- [ ] T041 [US4] Add `payment.processor` and the `mastercard.*` properties to `services/settlement-service/src/main/resources/application.yml`. Default is `simulated`.
- [ ] T042 [US4] Change `services/settlement-service/src/main/java/com/creditcard/settlement/batch/processor/SettlementItemProcessor.java`. When the processor is `mastercard` and the row is AUTHORIZED, call capture before building the `SettledItem`. On CAPTURED, continue. On anything else, call `lookup`, log the failure, and return null so Spring Batch filters the item. This makes T038 pass. The simulator branch must be the same code path as today.
- [ ] T043 [US4] Confirm `SettlementItemWriter` is unchanged. SETTLED is only written for items that passed the processor, so no change is needed. If a test shows otherwise, stop and report.
- [ ] T044 [US4] Add a settlement batch integration test in `SettlementBatchIntegrationTest` style, using the WireMock stand-in: one captured row ends SETTLED, one rejected row stays AUTHORIZED. Put it in a new file, `SettlementCaptureIntegrationTest.java`, and do not edit the existing one.

### Checkpoint

- [ ] T045 [US4] Checkpoint, plan phase 4. Run `./gradlew check` in settlement-service, 100% JaCoCo. Run the e2e with the default simulator and confirm the batch behaves as before. Confirm no card value or credential appears in test output. Show the per-file summary and stop for approval.

---

## Polish and cross-cutting

- [ ] T046 Update `specs/014-mastercard-mtf-processor/quickstart.md` for the phases in scope (1, 2, 4). Keep the Phase 3 section marked as blocked.
- [ ] T047 Update `specs/014-mastercard-mtf-processor/research.md`: record the confirmations from T021 and T037. Leave R7 marked provisional.
- [ ] T048 Add the deferred items to `docs/BACKLOG.md`: booking startup check for rule 2 (already deferred by 013), live MTF calls (Phase 3, blocked on the test merchant account), and the open decision on sharing the capture client.
- [ ] T049 Final gate: `./gradlew check` in every changed service, and `COMPOSE_PROJECT_NAME=credit_card scripts/e2e-test.sh`. Report the results. Do not push or open a PR without the owner's approval.

---

## Dependencies

- Phase 1 (setup) before everything. T002 and T003 must run before T011 to T014.
- Setup (T001 to T004) before everything. T002 and T003 must run before T011 to T014.
- Foundational (T005 to T008) blocks US2 and US4.
- US1 (plan phase 1) blocks US2, because the processor seam must exist first.
- US2 (plan phase 2) blocks US4 (plan phase 4), because settlement reuses the Mastercard mapping.
- Plan phase 3 (US3, live MTF) is not in this file.

## Parallel examples

- Setup: T002 and T003 in parallel.
- Foundational: T005, T006 and T007 in parallel (different files).
- US2: T026 and T027 in parallel (different services). T029 and T030 in parallel with T028.
- US4: T039 in parallel with T038.

## Implementation strategy

- MVP is US1 alone (plan phase 1). It proves the refactor and has no behavior change.
- Then US2 (plan phase 2), then US4 (plan phase 4). Each phase ends with a checkpoint and stops for approval.
- Within a phase, write the tests first, then the code, then run the checkpoint.
