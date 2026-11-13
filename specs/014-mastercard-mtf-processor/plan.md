# Implementation Plan: Mastercard Gateway Test Environment as Payment Processor

**Branch**: `014-mastercard-mtf-processor` | **Date**: 2026-11-12 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/014-mastercard-mtf-processor/spec.md`

## Summary

authorization-service decides approval through a `PaymentProcessor` interface. The
simulator keeps today's `card_accounts` logic and stays the default. A
`MastercardGatewayProcessor`, chosen by configuration, approves, declines, captures, and
voids through Mastercard Gateway's Version 100 REST API. Card details go from the browser or
a test tool to Mastercard under a Hosted Session. The backend receives only the session ID.
Our `transactionId` is the Mastercard order and transaction identifier, so retries cannot
double-authorize. Unknown results are resolved with a Mastercard lookup before any status is
set. Spec 013's timeout compensation moves behind the interface as `releaseHold`, which is the
simulator's credit release or Mastercard's VOID.

## Technical Context

**Language/Version**: Java 17 (LTS)

**Primary Dependencies**: Spring Boot 3.2.5, Spring Kafka, gRPC (grpc-spring-boot-starter),
Spring Data JPA, Flyway, Micrometer Tracing (already in the repo). HTTP client for Mastercard:
Spring `RestClient` (already on the classpath via Spring Boot web).

**Storage**: CockroachDB (existing per-service schemas). Additive Flyway migrations in
transaction-service and authorization-service. No new store.

**Testing**: JUnit 5, Mockito, AssertJ, Testcontainers (CockroachDB, Kafka), JaCoCo 100% gate
per service. WireMock (`wiremock-jre8-standalone:2.35.1`) is used in api-gateway tests today and
must be added as a test dependency to authorization-service in phase 2.

**Target Platform**: Docker Compose (local). GKE is out of scope.

**Project Type**: Microservice backend (payment core): transaction-service and
authorization-service, plus settlement-service in phase 4.

**Performance Goals**: No new target. Mastercard call latency is bounded by a client timeout
(configurable, default 10 s) so an unknown result is detected rather than hanging a Kafka
listener.

**Constraints**: No raw card data or CVV stored, logged, or put on Kafka (constitution
Principle I). Credentials from environment variables only. Simulator is the default. Each
phase must pass its tests before the next begins.

**Scale/Scope**: Four phases. About 3 new classes in authorization-service, 1 interface, 1
config record, 2 Flyway migrations, 1 proto field, 1 settlement step.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Note |
|---|---|---|
| I. Tokenization & Zero Raw Card Data | **Pass, with a review item** | Backend never receives PAN or CVV on the session path (proto fields for the session path are empty). CVV continues to be discarded upstream on the simulated path, as today. Review item: the test tool's server-side Update Session call (research R2) sends card data outside the system. It must not run in backend code or CI. Reviewer must confirm on the PR. |
| II. Test-First / 100% Coverage | **Pass** | Each phase: failing tests first, then code (Principle IV approval gates). JaCoCo gate unchanged. |
| III. Idempotent Event Consumers | **Pass** | `releaseHold` is applied through the `processed_events` key of the 013 timeout event. Mastercard retries are guarded by identifier reuse (research R3). |
| IV. Iterative One-Step Development | **Pass** | Phases are sequential. Class-level and method-level steps are sequenced in tasks.md. |
| V. Layered Architecture & Communication Tiers | **Pass** | The Mastercard call is not service-to-service. It is governed by Principle IX (added in 1.4.0), so Principle V's REST rule does not apply. The listener and repository layers stay unchanged. |
| IX. External Provider Calls (new in 1.4.0) | **Pass** | HTTPS with certificate verification; explicit connect and read timeouts; credentials from environment; no card data, session payload or secret logged; unknown result resolved by status lookup before any decision; our identifiers as the provider's order and transaction ids. Tests: unknown-result path and log-capture secret assertion (phase 2). |
| VI. Observability & Audit | **Pass** | Structured logs carry `transactionId`, operation, `result`, `reasonCode`. Session IDs masked. Trace ID propagated on the outbound call as a header (`traceparent`). |
| VII. Proto-First Contracts | **Pass** | `payment_session_id` is added to `proto/v1/transaction_service.proto` first. The new field number is additive. Contract tests against the generated stub come before the service change. |
| VIII. Frontend Code Sharing | **Not applicable** | No frontend change in this feature. |

**Security & Compliance** (checked against the constitution's standards):
- Secrets: environment variables only, masked in `toString()`. Not in source or images.
- TLS: Mastercard calls are HTTPS only. Certificate verification stays on (no trust-all client).
- Rate limiting: no change at the gateway.

**Gate G0 (satisfied 2026-11-12)**: Spec 013 is merged to `origin/main` (PR #14, commit
`6f2aacb`), with the e2e and migration guards (PR #15, commit `ab62b6c`). 014 is rebased onto
`origin/main`. Note: the local `main` branch was stale (still at 417b04f) when the rebase ran.
013 was built with option A plus option Y, no settlement `transaction.timed_out` consumer, and
the owner values P=300 s, I=30 s, K=60 s, m=60 s. Those values are in the timer table below.

**Constitution amendment (1.3.0 → 1.4.0)**: Principle IX, External Provider Calls, added by
owner decision. Scope: calls to third-party providers. It does not change Principles I–VIII
except the one cross-reference added to Principle V. The amendment lands on `main` with the
014 work or earlier, through the normal PR flow.

## Timer ordering (owner requirement)

Three timers interact. The sweep must never call `releaseHold` while an AUTHORIZE for the same
transaction is still in flight, and the transaction must resolve before booking's hold expires.

| Symbol | Timer | Value | Source |
|---|---|---|---|
The timer values and their config keys are the ones 013 already reserved in
`transaction-service/src/main/resources/application.yml` under `transaction.timeouts`. 014 adds
no new timer keys.

| Symbol | Timer | Value | Config key |
|---|---|---|---|
| `M` | Mastercard read timeout (AUTHORIZE, CAPTURE, VOID) | **10 s** (owner approved) | `transaction.timeouts.mastercard-read-timeout-seconds` |
| `b` | Status-retrieve buffer | **2 s** | `transaction.timeouts.status-retrieve-buffer-seconds` |
| `R` | Status-retrieve window `2·M + b` | **22 s** (derived) | none |
| `K` | Max Kafka delivery lag, transaction creation to authorization start | **60 s** (owner) | `transaction.timeouts.max-delivery-lag-seconds` |
| `P` | PENDING timeout (013) | **300 s** (owner) | `transaction.timeouts.pending-timeout-seconds` |
| `I` | Sweep interval (013) | **30 s** (owner) | `transaction.timeouts.sweep-interval-ms` |
| `d` | gRPC deadline on `InitiateTransaction` at checkout (013) | **5 s** | booking-service |
| `S` | Booking checkout window: `BOOKING_CHECKOUT_TIMEOUT_MINUTES × 60` from attempt creation | **600 s** (the 30 s poll adds slack, not counted) | `booking.checkout-timeout-minutes` |
| `m` | Required margin | **60 s** (owner) | `transaction.timeouts.margin-seconds` |

**Required ordering** (013 rules, unchanged by 014):

1. `P ≥ K + M + R + m` — `300 ≥ 152`. The sweep only acts on transactions old enough that any
   in-flight AUTHORIZE has finished, or timed out on its own, plus its status lookup.
2. `P + m + d ≤ S` — `365 ≤ 600`. A transaction times out before booking's checkout attempt
   expires, so booking records FAILED (retry) instead of EXPIRED followed by a late approval.
   013's design uses this form, with `d` included. The earlier `P + m ≤ S` form in this plan
   omitted `d`.
3. `I ≤ P` — `30 ≤ 300`.

**Mastercard adds no new timer rule.** `M` and `b` are already inputs to 013's
`TimerOrdering.checkTransactionRules`. Phase 2 only has to keep the Mastercard client's read
timeout equal to `transaction.timeouts.mastercard-read-timeout-seconds` (one source of truth).

**Safety guard, independent of the ordering:** `releaseHold` is a safe no-op when Mastercard
(or the simulator's authorization record) shows no authorization for that transaction. If the
AUTHORIZE is still in flight, the 013 timed-out marker blocks a later authorization. So a sweep
that runs early can never release a hold that does not exist, and can never leave a hold
behind.

**Startup validation:** transaction-service's `TimerOrdering` checks rules 1 and 3 at startup
(built in 013). The booking-service check for rule 2 was deferred by 013 to `docs/BACKLOG.md`,
so it is not a 014 task.

**Tests for the ordering:** 013's `TimerOrderingTest` (transaction-service) already covers
rules 1 to 3 with `M` and `b`. 014 adds no ordering test. The two behavior tests below cover
the processor side.
- `ReleaseHoldNoOpTest` (phase 1): `releaseHold` with no authorization returns `NO_OP` and does
  not change `available_credit`. The service then writes the timeout marker.
- `SweepVsInFlightAuthorizeTest` (phase 1): a transaction whose authorization is still in
  flight is not released. The timeout marker blocks the later authorization.

## Phases

Each phase ends with its tests green and an approval checkpoint. Phases do not start early.

### Phase 1 — Refactor only (no behavior change)

0. Gate G0 is satisfied (013 merged). 014 is rebased onto `origin/main` at `ab62b6c`.
1. Introduce `PaymentProcessor` (contracts/payment-processor.md) in authorization-service.
   `releaseHold` takes `(transactionId, cardToken)`. The simulator needs the card token to lock
   the card row, which the 013 release already does.
2. `SimulatedProcessor`: move the decision logic from `AuthorizationService.decide` into it
   (missing card → INVALID_CARD; inactive → CARD_NOT_ACTIVE; insufficient credit →
   INSUFFICIENT_CREDIT; otherwise debit and AUTHORIZED, plus the `authorization_records` row).
   `authorize` and `releaseHold` only. `lookup` returns `UNKNOWN` and `capture` returns
   `CAPTURE_REJECTED` because the simulator does not capture (settlement keeps its current
   behavior for the simulator; phase 4 is Mastercard-only).
3. Move the balance part of 013's `CreditReleaseService.release` into
   `SimulatedProcessor.releaseHold`: the AUTHORIZED record's amount goes back to
   `available_credit`, and the record becomes RELEASED. Behavior identical to 013.
   **What stays in the service layer:** the `processed_events` duplicate check for the
   `transaction.timed_out` event, and the TIMED_OUT marker written when there is no
   authorization record. These gate `authorize` for every processor, including Mastercard,
   where a `NO_OP` VOID cannot block a late AUTHORIZE. `CreditReleaseService` keeps its name and
   its `release(eventId, transactionId, cardToken)` method. Only its constructor changes, to take
   `PaymentProcessor`.
4. Existing tests and the e2e must pass unmodified (`COMPOSE_PROJECT_NAME=credit_card`). Any test
   edit beyond constructor wiring is a stop signal.

Tests first (Principle II): a characterization test per current approval outcome (approve,
decline by credit, blocked card, unknown card, invalid card) written against the current code
before the refactor, then the same tests against the interface.

### Phase 2 — Mastercard processor, selected by config, WireMock tests

1. Add `payment.processor` (default `simulated`) and `mastercard.api-version` (`100`,
   single value) as configuration. Unknown `payment.processor` value fails startup.
2. `MastercardGatewayProcessor` with the operations in contracts/mastercard-gateway.md:
   Authorize (session), Retrieve transaction (lookup), Void (`releaseHold`).
   Capture is not implemented in phase 2 (phase 4).
3. Response mapping table implemented exactly as in the contract. Unknown and timeout paths
   return `UNKNOWN` and trigger `lookup`.
4. WireMock tests for: approve, decline, timeout then lookup says approved, timeout then lookup
   says declined, timeout then lookup unknown (stays PENDING), duplicate-identifier response
   (lookup, no re-send), malformed body, VOID approved, VOID rejected (captured), 5xx.
5. Confirm the Retrieve transaction path and the duplicate-identifier error code on the
   version 100 reference page before the tests are finalized (research R3, R4). Record what was
   confirmed in research.md.

### Phase 3 — Live MTF calls (opt-in)

1. Gate G3a: confirm version 100 with the test merchant. A Create Session call returns
   `SUCCESS`. If not, change `mastercard.api-version` and repeat.
2. Gate G3b: confirm the test cards and their outcomes on the MTF portal's test-cards page.
   Update research R7 and quickstart.md with the confirmed values.
2a. Gate G3c: confirm the request and response formats for AUTHORIZE, VOID and CAPTURE against
   Mastercard Gateway's API reference or its official Postman collection. Check `targetTransactionId`,
   the amount and currency field names, and the success values (`result`, `response.gatewayCode`).
   Update the WireMock tests in `libs/mastercard-gateway` and the processors if anything differs.
   Until G3c, the Mastercard path is off by default and its formats are unconfirmed.
3. Credentials read from environment (`MASTERCARD_BASE_URL`, `MASTERCARD_MERCHANT_ID`,
   `MASTERCARD_API_PASSWORD`). Missing or wrong credentials fail the first payment with a clear
   configuration error. The Mastercard path never falls back to the simulator.
4. Session path added to the public API and gRPC contract (contracts/transaction-api.md):
   proto field first, contract tests on the generated stub, then the service change.
   Transaction-service validation rejects card fields combined with a session ID.
5. Opt-in live test profile (`@Tag("live-mtf")`) excluded from CI and from `./gradlew check`.
   Run manually with credentials exported.

### Phase 4 — Settlement capture

1. Settlement batch, when `payment.processor=mastercard`: CAPTURE each AUTHORIZED transaction
   before marking SETTLED. Simulator path unchanged. Hook point: `SettlementItemProcessor.process`
   (before the `SettledItem` is built). The row is set SETTLED in `SettlementItemWriter.write`, so
   the capture must succeed before that write.
2. Input path is unchanged by 013 option Y. Settlement's AUTHORIZED rows come from
   `transaction.authorized`, which transaction-service publishes through its outbox only when
   its conditional PENDING→AUTHORIZED update wins. A timed-out transaction never gets a row, so
   the batch cannot capture one. The capture uses the same `transactionId` as the Mastercard
   order and transaction id, so no new event field is needed.
3. Settlement cannot call authorization-service's `PaymentProcessor` (separate deployable,
   Principle V). Capture therefore needs a `MastercardCaptureClient` in settlement-service
   (recommended), or a shared module. Owner decision, see Open decisions.
4. Capture failure or unknown: row stays AUTHORIZED, `lookup` runs, failure reported.
5. Capture request fields confirmed on the version 100 Capture page (research R10) before
   implementation.

## Project Structure

### Documentation (this feature)

```text
specs/014-mastercard-mtf-processor/
├── spec.md
├── plan.md                  # this file
├── research.md              # Phase 0
├── data-model.md            # Phase 1
├── quickstart.md            # Phase 1
├── contracts/
│   ├── payment-processor.md
│   ├── transaction-api.md
│   └── mastercard-gateway.md
├── checklists/requirements.md
└── tasks.md                 # /speckit-tasks output, not created here
```

### Source code (repository root) — expected touch points

```text
proto/v1/transaction_service.proto                       # Phase 3: payment_session_id (additive)

services/authorization-service/src/main/java/com/creditcard/authorization/
├── service/CreditReleaseService.java                    # Phase 1 (keeps duplicate check + marker; calls releaseHold)
├── processor/
│   ├── PaymentProcessor.java                            # Phase 1
│   ├── ProcessorDecision.java                           # Phase 1
│   ├── AuthorizeRequest.java                            # Phase 1
│   ├── SimulatedProcessor.java                          # Phase 1 (moved logic)
│   ├── MastercardGatewayProcessor.java                  # Phase 2
│   ├── MastercardClient.java                            # Phase 2 (HTTP, version from config)
│   ├── MastercardProperties.java                        # Phase 2/3 (masked toString)
│   └── ProcessorConfig.java                             # Phase 2 (selection by property)
├── service/AuthorizationService.java                    # Phase 1 (delegates)
└── db/migration/V4__processor_columns.sql               # Phase 2 (additive)

services/transaction-service/src/main/java/com/creditcard/transaction/
├── api/grpc/TransactionGrpcService.java                 # Phase 3 (session path)
└── db/migration/V4__processor_columns.sql               # Phase 2 (additive)

services/settlement-service/src/main/java/com/creditcard/settlement/
└── batch/...                                            # Phase 4 (capture step)
```

Exact class names are confirmed in tasks.md. Layers follow Principle V: listener calls service;
service calls processor; processor performs the outbound HTTP call; repositories are called
only from the service layer.

## Complexity Tracking

Principle V's external-processor exception is resolved by amending the constitution to 1.4.0
(Principle IX), not by recording an exception here.

| Violation | Why Needed | Simpler Alternative Rejected Because |
| Session path added to the public API and proto | Required by FR-006: submit with a session ID instead of card data. | A separate endpoint was rejected because it would duplicate role and rate-limit rules. |
| Two new Flyway migrations (one per service) | New lifecycle facts (processor, decline reason, reference) must persist, and the constitution requires per-service schemas. | Storing this in the event stream alone loses it on replay. |

## Open decisions

Resolved by owner:
- 013 lands first (G0). Done: merged to `origin/main`.
- Principle IX added; constitution 1.4.0.
- Test-tool server-side fill through Postman's Update Session call: approved for MTF testing
  only, test cards only, never in backend code or CI. Documented in quickstart.md.
- Mastercard client timeout `M = 10 s`: approved.
- 013 values: `P = 300 s`, `I = 30 s`, `K = 60 s`, `m = 60 s`.
- 013 booking option A (transaction-service is the only publisher of final outcomes).
- 013 settlement: option Y. Settlement has no `transaction.timed_out` consumer.

Decided in this update (owner may redirect):
- `releaseHold(transactionId, cardToken)`. The timeout marker and the duplicate check stay in
  the service layer (Phase 1 step 3).
- The timer table uses 013's `transaction.timeouts.*` keys and `S = 600 s`.

Still open:
1. **Settlement capture client** (phase 4): a copy of a small `MastercardCaptureClient` in
   settlement-service (recommended, keeps service boundaries), or a shared module.
2. **Mastercard product**: owner confirming with mentor which product to use. Blocks any
   Mastercard-specific design decision beyond what the spec already states.
3. **Gate G3a**: API version 100, confirmed by a Create Session call with the test merchant.
4. **Gate G3b**: test cards, confirmed on the MTF test-cards page.
