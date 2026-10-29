---

description: "Task list for the room board (layer 2b)"
---

# Tasks: Room Board

**Input**: Design documents from `specs/009-room-board/` (plan.md, spec.md,
research.md, data-model.md, contracts/, quickstart.md)

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/,
quickstart.md — all present.

**Tests**: Included, and not optional for this project. Constitution Principle II
(NON-NEGOTIABLE) mandates test-first development with 100% JaCoCo instruction + branch
coverage for every class in every service; this standing project rule, not the feature
spec itself, is what makes test tasks mandatory here.

**Organization**: Tasks are grouped by user story (from spec.md: US1 = P1 board, US2 =
P2 check-in, US3 = P3 check-out, US4 = P4 room-status updates).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (US1/US2/US3/US4)
- File paths are exact, relative to the repository root

## Path Conventions

Purely additive changes inside the existing `services/booking-service/` module (no
new module) and the existing `services/api-gateway/` route/security config — per
plan.md's Project Structure.

---

## Phase 1: Setup

**Purpose**: The room-layout schema change, additive only (plan.md Key Design
Decision #1) — nothing in Foundational or any user story can build against the new
`floor`/`position`/`status` columns until this exists.

- [ ] T001 Flyway migration `services/booking-service/src/main/resources/db/migration/V5__add_room_layout_and_status.sql`:
      add `floor`/`position` (nullable, then backfilled from each existing room's
      `room_number`, then `NOT NULL`), add `status` (`NOT NULL DEFAULT
      'VACANT_CLEAN'`), insert 4 new rows (`303`-`306`) to reach 18, add
      `UNIQUE (floor, position)` — never drop a column, the table, or touch an
      existing room's `id` (research.md §1)

**Checkpoint**: Migration applies cleanly against a fresh CockroachDB; all 18 rows
exist with a unique `(floor, position)` and `status = VACANT_CLEAN`.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Domain/repository changes every user story in this layer depends on.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [ ] T002 [P] `services/booking-service/src/main/java/com/creditcard/booking/domain/RoomStatus.java`
      — `VACANT_CLEAN`, `OCCUPIED`, `DIRTY`, `MAINTENANCE` (data-model.md)
- [ ] T003 [P] `services/booking-service/src/main/java/com/creditcard/booking/domain/Room.java`
      — add `floor` (Integer), `position` (Integer), `status` (RoomStatus) fields +
      getters/setters
- [ ] T004 [P] `services/booking-service/src/main/java/com/creditcard/booking/domain/BookingStatus.java`
      — add `CHECKED_IN`, `CHECKED_OUT`
- [ ] T005 `services/booking-service/src/main/java/com/creditcard/booking/repository/RoomRepository.java`
      — add `updateStatusIfCurrent(id, newStatus, expected)` (conditional `@Modifying
      @Query UPDATE`, research.md §2) and `findAllByOrderByFloorAscPositionAsc()`
      (depends on T002, T003)
- [ ] T006 `services/booking-service/src/main/java/com/creditcard/booking/repository/BookingRepository.java`
      — add `updateStatusIfCurrent(id, newStatus, expected)` (same conditional-update
      pattern), `findByStatus(status)`, `findByRoomIdAndStatus(roomId, status)`
      (depends on T004)
- [ ] T007 [P] `services/booking-service/src/main/java/com/creditcard/booking/exception/InvalidBookingStateException.java`
      — check-in/check-out precondition failed (409)
- [ ] T008 [P] `services/booking-service/src/main/java/com/creditcard/booking/exception/InvalidRoomStatusTransitionException.java`
      — `PATCH /status` rejected (409)
- [ ] T009 `services/booking-service/src/main/java/com/creditcard/booking/exception/GlobalExceptionHandler.java`
      — map the two new exceptions to 409 (depends on T007, T008)

**Checkpoint**: Domain/repository/exception classes compile; user story implementation
can now begin.

---

## Phase 3: User Story 1 — Staff View the Room Board (Priority: P1) 🎯 layer MVP

**Goal**: Front-desk or housekeeping staff see every room, grouped by floor and
ordered by position, with its current status (and guest details if occupied).

**Independent Test**: Seed the layout, call the board endpoint, confirm every room
appears exactly once, correctly grouped/ordered, with no guest details on any
non-occupied room (spec.md Story 1).

### Tests for User Story 1 ⚠️ (write first, confirm they fail)

- [ ] T010 [P] [US1] Unit test: `RoomService.getBoard()` groups by floor, orders by
      position within each floor, and includes guest name/dates only for `OCCUPIED`
      rooms, in `services/booking-service/src/test/java/com/creditcard/booking/service/RoomServiceTest.java`
- [ ] T011 [P] [US1] Contract test: `GET /api/v1/rooms/board` in
      `services/booking-service/src/test/java/com/creditcard/booking/controller/RoomControllerTest.java`

### Implementation for User Story 1

- [ ] T012 [US1] DTOs `services/booking-service/src/main/java/com/creditcard/booking/controller/dto/RoomBoardResponse.java`
      (a floor + its ordered rooms, each with status and optional guest name/dates —
      contracts/api.md)
- [ ] T013 [US1] `services/booking-service/src/main/java/com/creditcard/booking/service/RoomService.java`
      (new) — `getBoard()`: two queries (all rooms ordered, all `CHECKED_IN`
      bookings), joined in memory by `roomId` (research.md §3) (depends on T005, T006)
- [ ] T014 [US1] `services/booking-service/src/main/java/com/creditcard/booking/controller/RoomController.java`
      — add `GET /api/v1/rooms/board` (depends on T012, T013)
- [ ] T015 [US1] Add the `room-board` route in
      `services/api-gateway/src/main/java/com/creditcard/gateway/config/RouteConfig.java`
      (existing `booking-service-cb` breaker — this route never calls gRPC)
- [ ] T016 [US1] Add a `.pathMatchers(GET, "/api/v1/rooms/board").hasAnyRole("FRONT_DESK",
      "HOUSEKEEPING", "ADMIN")` matcher, placed **before** the existing broad
      `/api/v1/bookings/**`+`/api/v1/rooms/**` rule, in
      `services/api-gateway/src/main/java/com/creditcard/gateway/config/SecurityConfig.java`
      (research.md §4)

**Checkpoint**: User Story 1 is independently functional — the board is viewable and
correctly shaped even though nothing has checked in yet.

---

## Phase 4: User Story 2 — Front Desk Checks a Guest In (Priority: P2)

**Goal**: A confirmed booking's guest arrives; front-desk staff check them in, the
booking becomes `CHECKED_IN`, the room becomes `OCCUPIED`, both immediately visible on
the board.

**Independent Test**: Confirm a booking (layer 2a saga), check it in, confirm the
booking's status, the room's status, and that the board reflects both (spec.md
Story 2).

### Tests for User Story 2 ⚠️

- [ ] T017 [P] [US2] Unit test: `BookingService.checkIn()` — success path (`CONFIRMED`
      + `VACANT_CLEAN` → `CHECKED_IN`/`OCCUPIED`), rejection when the booking isn't
      `CONFIRMED`, rejection when the room isn't `VACANT_CLEAN`, and the
      conditional-update path that surfaces a lost concurrent race as the same
      rejection (research.md §2) in `services/booking-service/src/test/java/com/creditcard/booking/service/BookingServiceTest.java`
- [ ] T018 [P] [US2] Integration test (Testcontainers CockroachDB — no Kafka needed
      for this layer): full check-in flow reflected on the board in
      `services/booking-service/src/test/java/com/creditcard/booking/integration/CheckInFlowIT.java`

### Implementation for User Story 2

- [ ] T019 [US2] `services/booking-service/src/main/java/com/creditcard/booking/service/BookingService.java`
      — add `checkIn(bookingId)`: validate `CONFIRMED`, conditionally update the room
      (`VACANT_CLEAN`→`OCCUPIED`) then the booking (`CONFIRMED`→`CHECKED_IN`), room
      before booking, both in one transaction (plan.md Key Design Decision #3)
      (depends on T005, T006, T007)
- [ ] T020 [US2] `services/booking-service/src/main/java/com/creditcard/booking/controller/BookingController.java`
      — add `POST /api/v1/bookings/{id}/check-in` (depends on T019)
- [ ] T021 [US2] Add the `booking-check-in` route in `RouteConfig.java` — falls
      through to the existing `FRONT_DESK`/`ADMIN` rule in `SecurityConfig.java`
      unchanged, no new matcher needed (research.md §4)

**Checkpoint — STOP HERE for this turn.** Setup + Foundational + User Story 1 (board)
+ User Story 2 (check-in) complete: a staff member can view the board, confirm a
booking, and check it in, watching the room flip to `OCCUPIED` on the board. Show
tests passing, coverage, and a live demo before continuing to Phase 5.

---

## Phase 5: User Story 3 — Front Desk Checks a Guest Out (Priority: P3)

**Goal**: At the end of a stay, front-desk staff check the guest out; the booking
becomes `CHECKED_OUT`, the room becomes `DIRTY`, with no payment of any kind.

**Independent Test**: Check a booking in, then out, confirm the booking's status, the
room's `DIRTY` status, and that no payment-related call or record exists (spec.md
Story 3).

### Tests for User Story 3 ⚠️

- [ ] T022 [P] [US3] Unit test: `BookingService.checkOut()` — success path, rejection
      when the booking isn't `CHECKED_IN`, and that the room's update is
      **unconditional** (always `DIRTY`, regardless of the room's own prior status —
      plan.md Key Design Decision #3) in `BookingServiceTest.java`
- [ ] T023 [P] [US3] Integration test: check-in → check-out, board shows `DIRTY`, no
      payment-related side effect, in `services/booking-service/src/test/java/com/creditcard/booking/integration/CheckOutFlowIT.java`

### Implementation for User Story 3

- [ ] T024 [US3] `BookingService.java` — add `checkOut(bookingId)`: validate
      `CHECKED_IN`, conditionally update the booking (`CHECKED_IN`→`CHECKED_OUT`) then
      unconditionally set the room to `DIRTY`, both in one transaction (depends on
      T019 — same file)
- [ ] T025 [US3] `BookingController.java` — add `POST /api/v1/bookings/{id}/check-out`
      (depends on T024)
- [ ] T026 [US3] Add the `booking-check-out` route in `RouteConfig.java` — same
      fall-through as check-in, no `SecurityConfig` change

**Checkpoint**: User Stories 1-3 all independently functional — the full stay
lifecycle (confirm → check-in → check-out) now plays out and shows correctly on the
board.

---

## Phase 6: User Story 4 — Housekeeping Updates Room Status (Priority: P4)

**Goal**: Housekeeping (or front desk) moves a room through cleaning, or takes it in
and out of service, so the board — and room availability — reflect reality.

**Independent Test**: Take a `DIRTY` room to `VACANT_CLEAN`, a `VACANT_CLEAN`/`DIRTY`
room to `MAINTENANCE`, and back — confirm every disallowed transition is rejected and
a `MAINTENANCE` room disappears from availability search (spec.md Story 4).

### Tests for User Story 4 ⚠️

- [ ] T027 [P] [US4] Unit test: `RoomService.updateStatus()` — each allowed transition
      (`DIRTY→VACANT_CLEAN`, `VACANT_CLEAN/DIRTY→MAINTENANCE`, `MAINTENANCE→VACANT_CLEAN`)
      succeeds; every other transition (including any transition *out of* `OCCUPIED`)
      is rejected with the room's status left unchanged; a lost concurrent race
      surfaces as the same rejection, in `RoomServiceTest.java`
- [ ] T028 [P] [US4] Unit test: `RoomRepository.findAvailable` excludes a
      `MAINTENANCE` room from a date range even when that room has no conflicting
      `booking_room_nights` row at all, in `services/booking-service/src/test/java/com/creditcard/booking/repository/RoomRepositoryTest.java`
      (or the existing `RoomAvailabilityServiceTest.java`, extended)
- [ ] T029 [P] [US4] Integration test: the full e2e-step-14 cycle (check-in →
      check-out → housekeeping cleans → `VACANT_CLEAN`) and the `MAINTENANCE`/
      availability-search interaction, in `services/booking-service/src/test/java/com/creditcard/booking/integration/RoomStatusFlowIT.java`

### Implementation for User Story 4

- [ ] T030 [US4] DTO `services/booking-service/src/main/java/com/creditcard/booking/controller/dto/RoomStatusUpdateRequest.java`
- [ ] T031 [US4] `RoomService.java` — add `updateStatus(roomId, requested)`: validate
      the `(current, requested)` pair against the fixed allowed-transition set, then
      apply via the same conditional update as check-in/out (plan.md Key Design
      Decision #4) (depends on T005)
- [ ] T032 [US4] `RoomController.java` — add `PATCH /api/v1/rooms/{id}/status`
      (depends on T030, T031)
- [ ] T033 [US4] `RoomRepository.java` — add the `status <> MAINTENANCE` exclusion to
      `findAvailable`'s JPQL query (FR-014)
- [ ] T034 [US4] Add the `room-status-update` route in `RouteConfig.java`
- [ ] T035 [US4] Add a `.pathMatchers(PATCH, "/api/v1/rooms/{id}/status").hasAnyRole(
      "FRONT_DESK", "HOUSEKEEPING", "ADMIN")` matcher, placed before the broad rule, in
      `SecurityConfig.java` (same pattern as T016)

**Checkpoint**: All four user stories independently functional — the full layer 2b
lifecycle, including the `MAINTENANCE` path, works end to end.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Wiring this layer's one new e2e step and closing out the coverage gate.
No `docker-compose.yml` or `ci.yml` change is needed — `booking-service`'s entry and
health-wait already exist from layer 2a.

- [ ] T036 [P] Run every scenario in `specs/009-room-board/quickstart.md` end to end
- [ ] T037 Add e2e step 14 (book → confirm → check-in → board shows `OCCUPIED` →
      check-out → `DIRTY` → housekeeping cleans → `VACANT_CLEAN`) to `scripts/e2e-test.sh`
- [ ] T038 Verify 100% JaCoCo instruction + branch coverage for `booking-service`
      (Principle II gate) and fix any gap
- [ ] T039 Confirm `docker-compose.yml` and `.github/workflows/ci.yml` need no changes
      for this layer (already cover `booking-service` from layer 2a) — verify only

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — the migration can run immediately.
- **Foundational (Phase 2)**: Depends on Setup — BLOCKS all user stories.
- **User Story 1 (Phase 3)**: Depends on Foundational only.
- **User Story 2 (Phase 4)**: Depends on Foundational; independently testable once its
  own tasks land. **This turn stops after this phase.**
- **User Story 3 (Phase 5)**: Depends on Foundational and reuses `BookingService`
  (T019's file) but adds no new dependency on US2's HTTP-layer tasks.
- **User Story 4 (Phase 6)**: Depends on Foundational only (T005's repository method).
- **Polish (Phase 7)**: Depends on all four user stories being complete.

### Within Each User Story

- Tests are written first and confirmed to fail before implementation (Principle II).
- Repositories/domain (Foundational) before services; services before controllers/
  gateway wiring.

### Parallel Opportunities

- Foundational T002-T004 (different files) and T007-T008 run in parallel.
- All test tasks within a story marked [P] run in parallel (different test files).
- User Stories 3 and 4 can both start as soon as Foundational is done — neither
  depends on the other, only on Phase 2.

---

## Parallel Example: User Story 2

```bash
# Tests for User Story 2, together:
Task: "Unit test for BookingService.checkIn() in .../service/BookingServiceTest.java"
Task: "Integration test for the check-in flow in .../integration/CheckInFlowIT.java"
```

---

## Implementation Strategy

### This turn: through the explicit checkpoint

1. Phase 1: Setup (the migration)
2. Phase 2: Foundational (blocks everything)
3. Phase 3: User Story 1 (board)
4. Phase 4: User Story 2 (check-in)
5. **STOP**: tests passing, coverage, a live demo on docker-compose — then wait for
   the go-ahead before Phase 5.

### Afterward: Incremental Delivery

1. Phase 5: User Story 3 (check-out) → validate independently
2. Phase 6: User Story 4 (room-status updates, including the `MAINTENANCE`/
   availability interaction) → validate independently
3. Phase 7: Polish → e2e step 14, coverage gate

---

## Notes

- Per CLAUDE.md's per-phase approval convention, each phase above is implemented and
  committed as a whole before stopping — not task by task.
- Avoid: implementing Phase 5/6 work before Phase 4's explicit checkpoint is shown and
  acknowledged.
