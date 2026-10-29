# Implementation Plan: Booking Service

**Branch**: `008-booking-service` | **Date**: 2026-10-29 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/008-booking-service/spec.md`

## Summary

Add `booking-service`, a new B2B backend service that lets front-desk staff (JWT role
`FRONT_DESK`, enforced at `api-gateway`) search room availability and create hotel
bookings on a guest's behalf. A booking is held immediately on creation (with a
concurrency-safe, no-double-booking guarantee), payment is authorized through the
existing `transaction-service` gRPC contract (card data never touches booking-service's
storage or logs), and the booking is driven to CONFIRMED or CANCELLED by consuming the
existing `transaction.status.updated` Kafka event idempotently. A scheduled job expires
abandoned HELD bookings after a configurable window (default 10 minutes). CONFIRMED and
CANCELLED are terminal — there are no reverse transitions, including for a late-arriving
AUTHORIZED outcome after expiry (FR-019/FR-023). This is layer "2a Booking" of the
Roadmap in `CLAUDE.md`; it reuses the payment core as-is and does not modify any existing
payment service. Per the current step's wiring-scope clarification, this layer's delivery
surface is docker-compose + CI + e2e steps 12-13 only — Kubernetes/GKE wiring for
booking-service is deferred to the backlog.

## Technical Context

**Language/Version**: Java 17

**Primary Dependencies**: Spring Boot 3.2.5 (Web, Data JPA, Validation, Actuator),
`net.devh:grpc-client-spring-boot-starter` (gRPC client to `transaction-service`), Spring
Kafka, Flyway, Spring Cloud Gateway (route addition only, in `api-gateway`)

**Storage**: CockroachDB — new `booking` schema/database owned exclusively by
booking-service, migrated with Flyway (consistent with every other service's convention)

**Testing**: JUnit 5 + Mockito (unit), Spring Boot Test + Testcontainers (CockroachDB,
Kafka) for integration, gRPC stub contract tests, 100% JaCoCo instruction + branch
coverage (Principle II, NON-NEGOTIABLE)

**Target Platform**: Linux containers via Docker Compose locally; reachable only through
`api-gateway` (no direct host exposure needed beyond its own management port for health
checks)

**Project Type**: Backend microservice, added to the existing multi-module Gradle
monorepo alongside the other 7 services

**Performance Goals**: No new targets beyond the platform norm; booking creation and
search are simple synchronous CockroachDB-backed operations, not a throughput-critical
path at this layer's scale

**Constraints**: Zero raw card data ever persisted/logged (Principle I); every Kafka
consumer idempotent via a per-service `processed_events` table (Principle III); gRPC is
the only permitted synchronous inter-service call (Principle V) — REST-to-REST
service calls are prohibited; no change of any kind to `transaction-service` or any other
existing payment service (FR-018); CONFIRMED/CANCELLED terminal with no reverse
transitions (FR-023); wiring limited to docker-compose + CI + e2e for this layer — no
Kubernetes manifests (current step's clarification); gRPC calls to `transaction-service`
carry a 5s deadline and a timeout leaves the booking HELD rather than failing it; a
`transaction.status.updated` event that arrives before its booking's `transaction_id` is
committed is retried with bounded backoff rather than dropped or marked processed (Key
Design Decisions)

**Scale/Scope**: Single property, single room per booking, MVP room/booking CRUD surface
only — no pricing rules, folios, refunds, or multi-property (spec Assumptions)

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design — see bottom.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Tokenization & Zero Raw Card Data | PASS | booking-service accepts card details in the booking request body only to forward them, unpersisted and unlogged, to `transaction-service` via the existing gRPC contract; it never stores or logs a PAN/CVV itself. |
| II. Test-First / 100% Coverage | PASS | Same TDD + 100% JaCoCo gate as every other service; no exception requested. |
| III. Idempotent Event Consumers | PASS | `transaction.status.updated` consumer uses a local `processed_events` table, key `booking:transaction.status.updated:{eventId}`, consistent with the existing pattern. |
| IV. Iterative One-Step Development | PASS | This plan stops after Phase 1 design per the current step's explicit instruction; `/speckit-tasks` (and the one-class/one-method gating it drives) is a separate, later step requiring its own approval. |
| V. Layered Microservice Architecture & Communication Tiers | PASS | booking-service follows Controller/Service/Repository; calls `transaction-service` over gRPC (sync, with a bounded deadline — see Key Design Decisions) and consumes Kafka (async) — no new REST-to-REST calls. Constitution amended (v1.2.1 → v1.3.0) to add `booking-service` to Principle V's "Services in scope" list; the prior documentation gap is resolved. |
| VI. Observability & Audit Trail | PASS | Structured JSON logs, trace ID propagation via HTTP header into the gRPC call and Kafka consumption, masked/absent card data throughout. |
| VII. Proto-First Service Contracts | PASS | No new `.proto` file — booking-service reuses `proto/v1/transaction_service.proto` exactly as it exists today; no breaking change. |
| VIII. Frontend Code Sharing | N/A | This layer (2a) is backend-only; the floor-plan frontend is a later Roadmap layer (2d), explicitly out of scope here. |

No violations requiring Complexity Tracking justification.

## Project Structure

### Documentation (this feature)

```text
specs/008-booking-service/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output
├── contracts/           # Phase 1 output
│   ├── api.md
│   └── events.md
└── tasks.md             # Phase 2 output (/speckit-tasks — NOT created by this plan)
```

### Source Code (repository root)

```text
services/booking-service/
├── build.gradle                       # Mirrors api-gateway's proto/Gradle setup (protobuf
│                                       # plugin pointed at repo-root proto/, generated stubs
│                                       # excluded from JaCoCo, same as every proto-consuming
│                                       # service)
└── src/
    ├── main/
    │   ├── java/com/creditcard/booking/
    │   │   ├── BookingServiceApplication.java
    │   │   ├── controller/
    │   │   │   ├── RoomController.java            # GET /api/v1/rooms
    │   │   │   └── BookingController.java          # POST/GET /api/v1/bookings
    │   │   ├── service/
    │   │   │   ├── RoomAvailabilityService.java
    │   │   │   ├── BookingService.java              # saga orchestration
    │   │   │   └── HoldExpiryService.java           # @Scheduled job
    │   │   ├── repository/
    │   │   │   ├── RoomRepository.java
    │   │   │   ├── BookingRepository.java
    │   │   │   ├── BookingRoomNightRepository.java   # double-booking guard, see research.md
    │   │   │   └── ProcessedEventRepository.java
    │   │   ├── domain/
    │   │   │   ├── Room.java
    │   │   │   ├── Booking.java
    │   │   │   ├── BookingStatus.java                # HELD, CONFIRMED, CANCELLED
    │   │   │   └── CancellationReason.java           # DECLINED, FAILED, HOLD_EXPIRED, PAYMENT_AFTER_EXPIRY
    │   │   ├── grpc/
    │   │   │   └── TransactionServiceClientConfig.java  # BlockingStub bean, mirrors
    │   │   │                                             # api-gateway's GrpcClientConfig
    │   │   ├── kafka/
    │   │   │   ├── event/TransactionStatusEvent.java   # local envelope copy (no shared lib)
    │   │   │   └── TransactionStatusConsumer.java
    │   │   └── exception/
    │   │       └── GlobalExceptionHandler.java
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/
    │           ├── V1__create_rooms.sql
    │           ├── V2__create_bookings.sql
    │           ├── V3__create_booking_room_nights.sql
    │           └── V4__create_processed_events.sql
    └── test/
        ├── java/com/creditcard/booking/            # unit (JUnit5+Mockito) + integration
        │                                             # (@SpringBootTest + Testcontainers)
        │                                             # mirroring the package tree above
        └── resources/application-test.yml

services/api-gateway/src/main/java/com/creditcard/gateway/
├── config/
│   ├── RouteConfig.java        # MODIFIED: add booking-submit, booking-get, room-search
│   │                           # routes (direct REST proxy, no rewritePath, no gRPC
│   │                           # filter — booking-service already speaks plain REST)
│   └── SecurityConfig.java     # MODIFIED: add .pathMatchers("/api/v1/bookings/**",
│                               # "/api/v1/rooms/**").hasAnyRole("FRONT_DESK", "ADMIN")
└── fallback/FallbackController.java   # unchanged — its existing `default` switch branch
                                        # already serves /fallback/booking

docker-compose.yml               # MODIFIED: new booking-service entry + its CockroachDB
                                  # database bootstrap, same shape as every other service
.github/workflows/ci.yml         # MODIFIED: build/test booking-service in the existing
                                  # multi-module Gradle matrix
scripts/e2e-test.sh              # MODIFIED: new step 12 (book a room, reach CONFIRMED)
                                  # and step 13 (declined card -> CANCELLED, room released)
```

**Structure Decision**: Single new Gradle module, `services/booking-service/`, added to
the existing multi-module monorepo with the same internal layering every other service
uses (Controller → Service → Repository), the same per-service CockroachDB
schema-ownership convention, and the same Flyway/Kafka/gRPC/JaCoCo conventions. No new
top-level project type is introduced. Per the current step's clarification, **no**
`k8s/` paths are touched by this layer — only `docker-compose.yml`, CI, and e2e.

## Key Design Decisions

Full rationale and alternatives for each of these are in `research.md`; this section
states the decisions themselves as the plan's binding contract.

1. **Room-night cleanup is atomic with cancellation, in every cancellation path.**
   Whenever a booking transitions to `CANCELLED` — declined/failed payment (FR-011),
   hold expiry (FR-015) — the same CockroachDB transaction that updates
   `booking.status`/`cancellation_reason` also deletes that booking's rows from
   `booking_room_nights`. This is what makes the room immediately re-searchable
   (SC-003/SC-004): there is no separate cleanup step that could be skipped, delayed, or
   partially applied.
   ```java
   @Transactional
   void cancel(Booking booking, CancellationReason reason) {
       booking.setStatus(CANCELLED);
       booking.setCancellationReason(reason);
       bookingRepository.save(booking);
       bookingRoomNightRepository.deleteByBookingId(booking.getId());
   }
   ```
   The one case that does **not** delete rows is the late-`AUTHORIZED`-after-expiry
   handler (FR-019): by the time that event arrives the booking is already `CANCELLED`
   and its room-night rows are already gone (deleted at expiry time) — that handler only
   updates `cancellation_reason` to `PAYMENT_AFTER_EXPIRY` and logs a WARN; it never
   touches `booking_room_nights`.

2. **A `transaction.status.updated` event with no matching booking is a retryable
   failure, not a no-op.** `InitiateTransaction`'s gRPC response and the booking's own
   `transaction_id` column are written in one transaction, but the Kafka event can still
   win the race and arrive at the consumer first. If no booking matches
   `payload.transactionId`, the consumer MUST NOT write a `processed_events` row (the
   event hasn't actually been handled) — it throws, so the existing Kafka error-handling
   configuration retries delivery with bounded backoff. Only after retries are exhausted
   does it log at ERROR and stop retrying that record (standard dead-letter/skip
   behavior), rather than silently treating "booking not found yet" as "booking will
   never exist." This is a genuine race (lost on a sufficiently slow write), not a
   not-found error, so it must resolve itself as soon as the booking row commits.

3. **The gRPC call to `transaction-service` carries a 5-second deadline; a timeout
   leaves the booking HELD.** `TransactionServiceBlockingStub` is called with
   `.withDeadlineAfter(5, TimeUnit.SECONDS)`. Because the booking row is already
   persisted as HELD *before* this call is made (FR-006), a `DEADLINE_EXCEEDED` (or
   `UNAVAILABLE`) status is caught, logged at WARN, and the booking is simply left HELD
   with no `transaction_id` set — exactly the same outcome the spec already defines for
   "payment system temporarily unreachable at booking creation" (Edge Cases). Hold
   expiry (FR-015), not the gRPC call site, is what eventually resolves a booking whose
   payment call never got a timely answer — no new state or retry logic is introduced at
   the call site itself.

## Complexity Tracking

*No entries — Constitution Check reported no violations requiring justification.*

## Post-Design Constitution Re-Check

Re-checked after Phase 1 design (data-model.md, contracts/, quickstart.md): no new
violations introduced. The double-booking-prevention design (a unique constraint on
`booking_room_nights(room_id, stay_date)`, see research.md) stays entirely within
booking-service's own schema and requires no change to Principle V's communication
tiers. The RBAC enforcement decision (extending `api-gateway`'s existing
`SecurityConfig.authorizeExchange` rules, the same mechanism already used for
transaction/reporting routes) introduces no new security pattern. Gate status unchanged
from the pre-design check above.
