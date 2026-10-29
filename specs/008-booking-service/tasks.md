---

description: "Task list for booking-service (layer 2a)"
---

# Tasks: Booking Service

**Input**: Design documents from `specs/008-booking-service/` (plan.md, spec.md,
research.md, data-model.md, contracts/, quickstart.md)

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/,
quickstart.md — all present.

**Tests**: Included, and not optional for this project. Constitution Principle II
(NON-NEGOTIABLE) mandates test-first development with 100% JaCoCo instruction + branch
coverage for every class in every service; this standing project rule, not the feature
spec itself, is what makes test tasks mandatory here.

**Organization**: Tasks are grouped by user story (from spec.md: US1 = P1, US2 = P2,
US3 = P3) to enable independent implementation and testing of each.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (US1/US2/US3)
- File paths are exact, relative to the repository root

## Path Conventions

Single new Gradle module per plan.md's Project Structure:
`services/booking-service/src/main/java/com/creditcard/booking/...` and
`services/booking-service/src/test/java/com/creditcard/booking/...`, plus small, scoped
edits to the existing `services/api-gateway/`, `docker-compose.yml`,
`.github/workflows/ci.yml`, and `scripts/e2e-test.sh`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Scaffold the new Gradle module so it builds (empty) before any real code
is added.

- [X] T001 Create the `services/booking-service/` module skeleton (directory tree per
      plan.md's Project Structure) and add it to the root `settings.gradle`
- [X] T002 [P] Write `services/booking-service/build.gradle`: Spring Boot 3.2.5 (Web,
      Data JPA, Validation, Actuator), Flyway, `net.devh:grpc-client-spring-boot-starter`,
      Spring Kafka, the `protobuf` plugin pointed at the repo-root `proto/` directory,
      and a JaCoCo exclusion for generated gRPC stubs — mirrors
      `services/api-gateway/build.gradle`'s existing proto/JaCoCo setup exactly
- [X] T003 [P] Create `services/booking-service/src/main/resources/application.yml`:
      app port 8080, management port 8092, CockroachDB datasource placeholder,
      `booking.merchant-id: HOTEL-001`, `booking.hold-timeout-minutes: 10` (research.md §2)
- [X] T004 [P] Create `services/booking-service/src/main/java/com/creditcard/booking/BookingServiceApplication.java`

**Checkpoint**: `./gradlew :services:booking-service:build` succeeds on an empty app.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Schema, domain model, and cross-cutting config every user story depends on.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T005 Flyway migration `services/booking-service/src/main/resources/db/migration/V1__create_rooms.sql`
      (rooms table + seed rows — data-model.md Room)
- [X] T006 Flyway migration `services/booking-service/src/main/resources/db/migration/V2__create_bookings.sql`
      (bookings table, `status`/`cancellation_reason`/`transaction_id` columns —
      data-model.md Booking)
- [X] T007 Flyway migration `services/booking-service/src/main/resources/db/migration/V3__create_booking_room_nights.sql`
      (`booking_room_nights` table with `UNIQUE (room_id, stay_date)` — the sole
      double-booking guard, research.md §1)
- [X] T008 Flyway migration `services/booking-service/src/main/resources/db/migration/V4__create_processed_events.sql`
      (idempotency table — Principle III)
- [X] T009 [P] Domain classes in `services/booking-service/src/main/java/com/creditcard/booking/domain/`:
      `Room.java`, `Booking.java`, `BookingStatus.java` (HELD/CONFIRMED/CANCELLED),
      `CancellationReason.java` (PAYMENT_DECLINED/PAYMENT_FAILED/HOLD_EXPIRED/PAYMENT_AFTER_EXPIRY)
- [X] T010 [P] Repository interfaces in `services/booking-service/src/main/java/com/creditcard/booking/repository/`:
      `RoomRepository.java`, `BookingRepository.java` (incl. a query for HELD bookings
      older than a cutoff, for Phase 5), `BookingRoomNightRepository.java` (incl.
      `deleteByBookingId`), `ProcessedEventRepository.java`
- [X] T011 `services/booking-service/src/main/java/com/creditcard/booking/grpc/TransactionServiceClientConfig.java`
      — `@GrpcClient("transaction-service") Channel` bean exposing a
      `TransactionServiceBlockingStub`, mirroring `api-gateway`'s `GrpcClientConfig`
      pattern but with a blocking (not future) stub (research.md §2)
- [X] T012 [P] `services/booking-service/src/main/java/com/creditcard/booking/kafka/event/TransactionStatusEvent.java`
      — local envelope/payload DTO copy, same shape as
      `notification-service`'s (research.md §3)
- [X] T013 `services/booking-service/src/main/java/com/creditcard/booking/exception/GlobalExceptionHandler.java`
      — maps invalid-date-range → 400, room/booking-not-found → 404, unique-constraint
      violation on `booking_room_nights` → 409 (contracts/api.md)

**Checkpoint**: Schema migrates cleanly against CockroachDB; domain/repository/config
classes compile. User story implementation can now begin.

---

## Phase 3: User Story 1 — Staff Books a Room and Reaches a Confirmed Reservation (Priority: P1) 🎯 MVP

**Goal**: Front-desk staff (FRONT_DESK role) search availability, submit a booking that
goes HELD immediately, and watch it become CONFIRMED once payment is authorized.

**Independent Test**: Seed a room, search it available, book it with a card that
authorizes, confirm status reaches CONFIRMED with a `transactionId` — per spec.md's
Independent Test for this story and `quickstart.md`'s Story 1 walkthrough.

### Tests for User Story 1 ⚠️ (write first, confirm they fail)

- [X] T014 [P] [US1] Contract test for `GET /api/v1/rooms` (valid range, invalid range
      → 400) in `services/booking-service/src/test/java/com/creditcard/booking/controller/RoomControllerTest.java`
- [X] T015 [P] [US1] Contract test for `POST /api/v1/bookings` (happy path → 201 HELD;
      nonexistent room → 404) in `services/booking-service/src/test/java/com/creditcard/booking/controller/BookingControllerTest.java`
- [X] T016 [P] [US1] Contract test for `GET /api/v1/bookings/{id}` (found, 404 when not)
      in the same `BookingControllerTest.java`
- [X] T017 [P] [US1] Integration test (Testcontainers CockroachDB + Kafka): full saga
      HELD → AUTHORIZED event → CONFIRMED in `services/booking-service/src/test/java/com/creditcard/booking/integration/BookingConfirmedFlowIT.java`
- [X] T018 [P] [US1] Unit test: `RoomAvailabilityService` excludes rooms with a
      HELD/CONFIRMED night in the requested range in `services/booking-service/src/test/java/com/creditcard/booking/service/RoomAvailabilityServiceTest.java`
- [X] T019 [P] [US1] Unit/integration test: two concurrent booking attempts for the same
      room/overlapping dates — exactly one succeeds, the other gets 409 (research.md §1,
      FR-007) in `services/booking-service/src/test/java/com/creditcard/booking/service/BookingServiceConcurrencyTest.java`
- [X] T020 [P] [US1] Unit test: `TransactionStatusConsumer` HELD+AUTHORIZED → CONFIRMED,
      and the "no matching booking" race throws without writing `processed_events`
      (research.md §3a) in `services/booking-service/src/test/java/com/creditcard/booking/kafka/TransactionStatusConsumerTest.java`

### Implementation for User Story 1

- [X] T021 [US1] Implement `RoomAvailabilityService.findAvailable(checkIn, checkOut)` in
      `services/booking-service/src/main/java/com/creditcard/booking/service/RoomAvailabilityService.java`
      (depends on T009, T010)
- [X] T022 [US1] Implement `RoomController` (`GET /api/v1/rooms`, 400 on invalid range —
      FR-003) in `services/booking-service/src/main/java/com/creditcard/booking/controller/RoomController.java`
      (depends on T021)
- [X] T023 [US1] Implement `BookingService.createHeldBooking(request)` — one CockroachDB
      transaction inserting the `bookings` row and its `booking_room_nights` rows,
      catching the unique-constraint violation as "room unavailable" (research.md §1) in
      `services/booking-service/src/main/java/com/creditcard/booking/service/BookingService.java`
      (depends on T009, T010)
- [X] T024 [US1] Implement `BookingService.initiatePayment(booking, card)` — calls
      `TransactionServiceBlockingStub.initiateTransaction` with a 5s deadline
      (`withDeadlineAfter`), saves the returned `transactionId`; on `DEADLINE_EXCEEDED`/
      `UNAVAILABLE`, logs WARN and leaves the booking HELD with no `transactionId`
      (research.md §2, Key Design Decision #3) in
      `services/booking-service/src/main/java/com/creditcard/booking/service/BookingService.java`
      (depends on T011, T023)
- [X] T025 [US1] Implement `BookingController` `POST /api/v1/bookings` (calls
      `createHeldBooking` then `initiatePayment`, returns 201 HELD) in
      `services/booking-service/src/main/java/com/creditcard/booking/controller/BookingController.java`
      (depends on T023, T024)
- [X] T026 [US1] Implement `BookingController` `GET /api/v1/bookings/{id}` in the same
      `BookingController.java` (depends on T010)
- [X] T027 [US1] Implement `TransactionStatusConsumer`: look up booking by
      `payload.transactionId`; if none found, throw without writing `processed_events`
      (research.md §3a); if found and HELD with `AUTHORIZED`, transition to CONFIRMED
      and write `processed_events` in the same transaction (FR-010, FR-012, FR-016) in
      `services/booking-service/src/main/java/com/creditcard/booking/kafka/TransactionStatusConsumer.java`
      (depends on T012, T010)
- [X] T028 [US1] Add `booking-submit`/`booking-get`/`room-search` routes (direct REST
      proxy, `booking-service-cb` circuit breaker, `forward:/fallback/booking`) in
      `services/api-gateway/src/main/java/com/creditcard/gateway/config/RouteConfig.java`
- [X] T029 [US1] Add `.pathMatchers("/api/v1/bookings/**",
      "/api/v1/rooms/**").hasAnyRole("FRONT_DESK", "ADMIN")` in
      `services/api-gateway/src/main/java/com/creditcard/gateway/config/SecurityConfig.java`
      (FR-017)

**Checkpoint**: User Story 1 is independently functional and testable — a staff member
can search, book, and watch a booking reach CONFIRMED end to end.

---

## Phase 4: User Story 2 — Declined Payment Releases the Room (Priority: P2)

**Goal**: A declined/failed payment cancels the booking and frees the room immediately.

**Independent Test**: Book with a card that declines; status becomes CANCELLED and the
room reappears in availability search — per spec.md's Independent Test for this story.

### Tests for User Story 2 ⚠️

- [X] T030 [P] [US2] Integration test: HELD → DECLINED event → CANCELLED
      (`cancellationReason: PAYMENT_DECLINED`), room reappears in `GET /api/v1/rooms`
      in `services/booking-service/src/test/java/com/creditcard/booking/integration/BookingDeclinedFlowIT.java`
- [X] T031 [P] [US2] Unit test: `TransactionStatusConsumer` DECLINED/FAILED path deletes
      the booking's `booking_room_nights` rows in the same transaction as the status
      update (Key Design Decision #1); CANCELLED+DECLINED/FAILED is a no-op (FR-021) in
      `services/booking-service/src/test/java/com/creditcard/booking/kafka/TransactionStatusConsumerTest.java`

### Implementation for User Story 2

- [X] T032 [US2] Implement `BookingService.cancel(booking, reason)` — one transaction:
      update `status`/`cancellation_reason`, delete this booking's
      `booking_room_nights` rows (research.md "Cleanup on cancellation", Key Design
      Decision #1) in `services/booking-service/src/main/java/com/creditcard/booking/service/BookingService.java`
      (depends on T009, T010)
- [X] T033 [US2] Extend `TransactionStatusConsumer`: HELD + DECLINED/FAILED →
      `cancel(booking, PAYMENT_DECLINED|PAYMENT_FAILED)` (FR-011) in
      `services/booking-service/src/main/java/com/creditcard/booking/kafka/TransactionStatusConsumer.java`
      (depends on T027, T032)
- [X] T034 [US2] Extend `TransactionStatusConsumer`: any CANCELLED booking +
      DECLINED/FAILED → no-op, no state change (FR-021) in the same file (depends on T033)

**Checkpoint**: User Stories 1 and 2 both work independently; a declined card never
permanently locks a room.

---

## Phase 5: User Story 3 — Abandoned Bookings Expire Automatically (Priority: P3)

**Goal**: A HELD booking with no payment outcome is auto-cancelled after the configured
hold window, and a late `AUTHORIZED` outcome after that never reverses it.

**Independent Test**: Age a HELD booking past the hold window, run expiry, confirm
CANCELLED + room available — per spec.md's Independent Test for this story.

### Tests for User Story 3 ⚠️

- [X] T035 [P] [US3] Unit test: `HoldExpiryService` cancels HELD bookings older than
      `booking.hold-timeout-minutes` with `cancellationReason: HOLD_EXPIRED`; leaves
      fresh HELD and already-CONFIRMED/CANCELLED bookings untouched (spec.md US3
      Acceptance Scenarios 2–3) in `services/booking-service/src/test/java/com/creditcard/booking/service/HoldExpiryServiceTest.java`
- [X] T036 [P] [US3] Integration test: expire a booking, then confirm `GET /api/v1/rooms`
      shows the room available again in `services/booking-service/src/test/java/com/creditcard/booking/integration/HoldExpiryFlowIT.java`
- [X] T037 [P] [US3] Unit test: a late `AUTHORIZED` outcome for an already
      `HOLD_EXPIRED`-cancelled booking stays CANCELLED, with `cancellationReason`
      updated to `PAYMENT_AFTER_EXPIRY` and a WARN logged — never reverses to CONFIRMED,
      regardless of whether the room is still free (FR-019, FR-023) in
      `services/booking-service/src/test/java/com/creditcard/booking/kafka/TransactionStatusConsumerTest.java`

### Implementation for User Story 3

- [X] T038 [US3] Implement `HoldExpiryService` `@Scheduled(fixedDelay = ...)` job: find
      HELD bookings older than the configured timeout, call
      `BookingService.cancel(booking, HOLD_EXPIRED)` per booking (research.md §5) in
      `services/booking-service/src/main/java/com/creditcard/booking/service/HoldExpiryService.java`
      (depends on T032)
- [X] T039 [US3] Extend `TransactionStatusConsumer`: CANCELLED (reason `HOLD_EXPIRED`) +
      `AUTHORIZED` → stay CANCELLED, set `cancellationReason = PAYMENT_AFTER_EXPIRY`, log
      WARN for manual refund; never update `booking_room_nights` (already deleted at
      expiry) (FR-019, FR-023) in `services/booking-service/src/main/java/com/creditcard/booking/kafka/TransactionStatusConsumer.java`
      (depends on T027, T038)

**Checkpoint**: All three user stories are independently functional; the full saga,
including both terminal-state edge cases, behaves per spec.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Wiring this layer into the shared delivery surface (docker-compose + CI +
e2e only — no Kubernetes, per the current step's clarification) and closing out the
coverage gate.

- [X] T040 [P] Add a `booking-service` entry to `docker-compose.yml` (image build,
      env vars matching `application.yml`, CockroachDB database bootstrap step — same
      shape as every other service's entry)
- [X] T041 [P] Add `booking-service` to the Gradle build/test matrix in
      `.github/workflows/ci.yml`
- [X] T042 Add e2e step 12 (book a room, reach CONFIRMED) to `scripts/e2e-test.sh`
- [X] T043 Add e2e step 13 (declined card → CANCELLED, room available again) to
      `scripts/e2e-test.sh`
- [X] T044 Run every scenario in `specs/008-booking-service/quickstart.md` end to end,
      including the transactionId-race and gRPC-deadline edge cases
- [X] T045 Verify 100% JaCoCo instruction + branch coverage for `booking-service`
      (Principle II gate) and fix any gap
- [X] T046 [P] Log anything discovered during implementation that is out of this
      layer's spec in `docs/BACKLOG.md` instead of building it (CLAUDE.md "no
      speculative features")

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — start immediately.
- **Foundational (Phase 2)**: Depends on Setup — BLOCKS all user stories.
- **User Story 1 (Phase 3)**: Depends on Foundational only.
- **User Story 2 (Phase 4)**: Depends on Foundational; reuses `BookingService` and
  `TransactionStatusConsumer` scaffolding created in Phase 3 (T023, T027) but adds no
  new dependency on US1's HTTP-layer tasks — independently testable once its own tasks
  land.
- **User Story 3 (Phase 5)**: Depends on Foundational and on `BookingService.cancel`
  (introduced in Phase 4, T032) and the consumer skeleton (Phase 3, T027). The
  late-AUTHORIZED-after-expiry task (T039) necessarily follows expiry existing (T038).
- **Polish (Phase 6)**: Depends on all three user stories being complete.

### Within Each User Story

- Tests are written first and confirmed to fail before implementation (Principle II).
- Repositories/domain (Foundational) before services; services before controllers/
  consumers; story complete before moving to the next priority.

### Parallel Opportunities

- All Setup tasks marked [P] run in parallel.
- Foundational T009/T010/T012 (different files) run in parallel; T011 and T013 are
  standalone and also parallelizable with those.
- All test tasks within a story marked [P] run in parallel (different test files).
- User Story 2 and User Story 3's test-writing can start as soon as Phase 3's shared
  scaffolding (T023, T027, T032) exists, even before all of Phase 3's HTTP-layer tasks
  (T025, T026, T028, T029) are done — but implementation of US2/US3 still waits on T032/
  T038 respectively as noted above.

---

## Parallel Example: User Story 1

```bash
# Tests for User Story 1, together:
Task: "Contract test for GET /api/v1/rooms in .../controller/RoomControllerTest.java"
Task: "Contract test for POST /api/v1/bookings in .../controller/BookingControllerTest.java"
Task: "Integration test for the full HELD->CONFIRMED saga in .../integration/BookingConfirmedFlowIT.java"
Task: "Unit test for RoomAvailabilityService in .../service/RoomAvailabilityServiceTest.java"
Task: "Concurrency test for double-booking rejection in .../service/BookingServiceConcurrencyTest.java"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 1: Setup
2. Phase 2: Foundational (blocks everything)
3. Phase 3: User Story 1
4. **STOP and VALIDATE**: run quickstart.md's Story 1 scenario end to end
5. Demo: staff books a room, it reaches CONFIRMED

### Incremental Delivery

1. Setup + Foundational → foundation ready
2. User Story 1 → validate independently → demo (MVP)
3. User Story 2 → validate independently → demo (declined card releases the room)
4. User Story 3 → validate independently → demo (abandoned bookings expire, and a late
   AUTHORIZED never reverses expiry)
5. Phase 6 → wire into docker-compose/CI/e2e, close the coverage gate

---

## Notes

- Per CLAUDE.md's Iterative One-Step Development convention, each task above still
  requires its own read-test-confirm-approve cycle before moving to the next — this
  task list is the plan for that sequence, not a license to batch-implement it.
- Avoid: implementing a later story's tasks before its own predecessor tasks (T032
  before T023, T038 before T032, T039 before T038) — each depends on the one before it
  exactly as called out above.
