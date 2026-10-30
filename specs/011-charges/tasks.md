---

description: "Task list for Charges (layer 2c)"
---

# Tasks: Charges

**Input**: Design documents from `specs/011-charges/` (plan.md, spec.md, research.md,
data-model.md, contracts/, quickstart.md)

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/,
quickstart.md — all present.

**Tests**: Included, and not optional for this project. Constitution Principle II
(NON-NEGOTIABLE) mandates 100% test coverage for every class in every service, and
layer 2d's own precedent extends that to the frontend (Vitest coverage thresholds).

**Organization**: Tasks are grouped by user story (from spec.md: US1 = P1 add a
charge, US2 = P2 balance-due board flag, US3 = P3 checkout with a balance, US4 = P4
checkout with no balance).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: Which user story this task belongs to (US1-US4)
- File paths are exact, relative to the repository root

## Path Conventions

Additive changes inside the existing `services/booking-service/` module, the
existing `services/api-gateway/` route/security config, the existing `frontend/`
app (extending layer 2d's own `booking`/`room-board` features), and one
test-harness correctness fix in `scripts/e2e-test.sh` — per plan.md's Project
Structure. No new service, no new module, no new frontend area.

---

## Phase 1: Setup

**Purpose**: The test-harness root-cause fix and the schema migration — neither
depends on, nor is depended on by, any user story, but both should land first so
the rest of this layer is built and verified against a correct foundation.

- [X] T001 `scripts/e2e-test.sh`: `setup_auth_mock` records whether a container
      named `auth-service` already existed *before* it replaces it
      (`PREEXISTING_AUTH_MOCK=1`/`0`); `cleanup()` runs `docker compose up -d
      auth-mock` to restore it when that flag is set, instead of unconditionally
      leaving nothing behind (research.md's own gap from layer 2d, now fixed at
      the root per this layer's own Clarifications-adjacent instruction)
- [X] T002 `.github/workflows/ci.yml`: remove the "Restart auth-mock" step added in
      layer 2d — no longer needed once T001 lands (depends on T001)
- [X] T003 Flyway migration
      `services/booking-service/src/main/resources/db/migration/V6__add_charges_and_checkout_payments.sql`:
      `charges` table, `checkout_payment_attempts` table, `bookings.room_charge_amount`
      column — additive only, no existing column changed or dropped (data-model.md)

**Checkpoint**: `docker compose up -d --build` then a full `scripts/e2e-test.sh`
run leaves `auth-service` running afterward (verify with `docker compose ps
auth-mock`), matching what it looked like before the run. Migration applies
cleanly against a fresh CockroachDB.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Domain/repository/DTO shapes every user story depends on.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T004 [P] `services/booking-service/src/main/java/com/creditcard/booking/domain/ChargeType.java`
      — `ROOM_SERVICE`, `MINIBAR`, `LAUNDRY`, `OTHER`
- [X] T005 [P] `services/booking-service/src/main/java/com/creditcard/booking/domain/CheckoutAttemptStatus.java`
      — `PENDING`, `AUTHORIZED`, `DECLINED`, `FAILED`, `EXPIRED`, `LATE_AUTHORIZED`
      (data-model.md's state machine)
- [X] T006 [P] `services/booking-service/src/main/java/com/creditcard/booking/domain/Charge.java`
      — id, bookingId, type, description, amount, createdAt; add-only, no setter
      beyond construction needed
- [X] T007 [P] `services/booking-service/src/main/java/com/creditcard/booking/domain/CheckoutPaymentAttempt.java`
      — id, bookingId, transactionId (nullable), amount, status, createdAt, updatedAt
- [X] T008 [P] `services/booking-service/src/main/java/com/creditcard/booking/domain/BookingStatus.java`
      — add `CHECKOUT_PENDING`
- [X] T009 `services/booking-service/src/main/java/com/creditcard/booking/domain/Booking.java`
      — add `roomChargeAmount` field (nullable, set once in `initiatePayment`, plan.md
      Key Design Decision #1) (depends on T008 for the status enum it coexists with)
- [X] T010 [P] `services/booking-service/src/main/java/com/creditcard/booking/repository/ChargeRepository.java`
      — `findByBookingIdOrderByCreatedAtAsc(UUID)`, `findByBookingIdIn(List<UUID>)`
      (depends on T006)
- [X] T011 [P] `services/booking-service/src/main/java/com/creditcard/booking/repository/CheckoutPaymentAttemptRepository.java`
      — `findByTransactionId(String)`, `findByBookingIdInAndStatusIn(List<UUID>,
      List<CheckoutAttemptStatus>)` (for board balance calc), `findByStatusAndCreatedAtBefore(CheckoutAttemptStatus,
      Instant)` (for the sweep, research.md §4) (depends on T005, T007)
- [X] T012 `services/booking-service/src/main/java/com/creditcard/booking/service/BookingService.java`:
      `initiatePayment` sets `roomChargeAmount` on the booking alongside `transactionId`
      (depends on T009)
- [X] T013 [P] `services/booking-service/src/main/java/com/creditcard/booking/controller/dto/ChargeResponse.java`
      — chargeId, type, description, amount, createdAt (depends on T004)
- [X] T014 [P] `services/booking-service/src/main/java/com/creditcard/booking/controller/dto/ChargeRequest.java`
      — type, description, amount, all `@NotNull`/`@NotBlank`/`@Positive` as
      appropriate (depends on T004)
- [X] T015 `services/booking-service/src/main/java/com/creditcard/booking/controller/dto/BookingDetailResponse.java`
      — add `charges: List<ChargeResponse>`, `balanceDue: BigDecimal` (depends on T013)
- [X] T016 [P] `frontend/src/types/staff.ts`: add `Charge`, `ChargeType`; extend
      `Booking` with `charges`/`balanceDue`; extend `RoomBoardEntry` with
      `hasBalanceDue: boolean | null`

### Tests for Foundational

- [X] T017 [P] `services/booking-service/src/test/java/com/creditcard/booking/service/BookingServiceTest.java`
      (extended): `initiatePayment` persists `roomChargeAmount` equal to the exact
      amount the gRPC request was built from

**Checkpoint**: Foundation compiles; T017 passes at 100% coverage for the files it
touches.

---

## Phase 3: User Story 1 — Staff Add a Charge During a Stay (Priority: P1) 🎯 layer MVP

**Goal**: Front-desk staff add a charge to a checked-in booking and see the
resulting balance immediately.

**Independent Test**: Check a booking in, add charges of different types, confirm
each is its own line item and the balance due matches (spec.md Story 1).

### Tests for User Story 1 ⚠️ (write first, confirm they fail)

- [ ] T018 [P] [US1] Unit test: `BookingService.addCharge()` — succeeds for a
      `CHECKED_IN` booking; rejects every other status, recording nothing; balance
      calc sums charges correctly (the `roomChargeAmount` terms cancel, per FR-004) —
      in `BookingServiceTest.java`
- [ ] T019 [P] [US1] Contract test: `POST /api/v1/bookings/{id}/charges` (201 + body
      shape, 409 for a non-`CHECKED_IN` booking), `GET .../charges` (200, ordered
      list) — in `services/booking-service/src/test/java/com/creditcard/booking/controller/BookingControllerTest.java`
- [ ] T020 [P] [US1] Integration test (real CockroachDB): check in, add two charges
      of different types, confirm `GET /api/v1/bookings/{id}` returns both as line
      items with the correct `balanceDue` — new
      `services/booking-service/src/test/java/com/creditcard/booking/integration/ChargeFlowIT.java`
- [ ] T021 [P] [US1] `frontend/src/features/booking/ChargesPanel.test.tsx` (MSW):
      renders existing charges and balance; submitting the add-charge form calls
      the mutation with the right payload and the panel updates without a manual
      refresh (cache-tag invalidation, research-adjacent — see T028)
- [ ] T022 [P] [US1] `frontend/src/features/room-board/RoomActionPanel.test.tsx`
      (extended): an `OCCUPIED` room's panel renders `ChargesPanel` with that
      booking's current charges/balance

### Implementation for User Story 1

- [ ] T023 [US1] `BookingService.java`: `addCharge(bookingId, ChargeRequest)` —
      validates `CHECKED_IN`, persists a `Charge`, returns it (depends on T006, T014)
- [ ] T024 [US1] `BookingService.java`: a `getBalanceDue(Booking)` helper — sums
      `ChargeRepository.findByBookingIdOrderByCreatedAtAsc`'s amounts plus
      `roomChargeAmount`, minus `roomChargeAmount` and the sum of `AUTHORIZED`/
      `LATE_AUTHORIZED` `CheckoutPaymentAttempt` amounts (FR-004) (depends on T010, T011)
- [ ] T025 [US1] `BookingController.java`: `POST /{id}/charges`, `GET /{id}/charges`;
      extend `GET /{id}` to include `charges`/`balanceDue` via T024 (depends on T023, T024)
- [ ] T026 [US1] `services/api-gateway/.../config/RouteConfig.java`: add
      `booking-charges-get`/`booking-charges-post` routes (reuse `booking-service-cb`);
      `SecurityConfig.java`: add `GET /api/v1/bookings/*` and `GET
      /api/v1/bookings/*/charges` matchers (`FRONT_DESK`, `HOUSEKEEPING`, `ADMIN`),
      placed before the existing broad rule — same pattern as the room board's own
      matcher (research.md §ref: layer 2b/2d precedent)
- [ ] T027 [US1] `frontend/src/features/booking/bookingApi.ts`: `addCharge`
      mutation; add RTK Query cache tags (`Booking`) to `getBooking` (`providesTags`)
      and `addCharge`/`checkIn`/`checkOut` (`invalidatesTags`) so adding a charge is
      reflected immediately, no polling wait (spec.md SC-001) (depends on T016)
- [ ] T028 [US1] `frontend/src/features/booking/ChargesPanel.tsx`: charge-type
      select, description, amount fields; charges list; balance display (depends on T027)
- [ ] T029 [US1] `frontend/src/features/room-board/RoomActionPanel.tsx`: the
      `OCCUPIED` branch fetches that booking's detail (`useGetBookingQuery`) and
      renders `ChargesPanel` alongside the existing check-out action (depends on T028)

**Checkpoint**: Staff can check in, add charges of every type, and see the balance
update immediately. Tests passing, 100% coverage.

---

## Phase 4: User Story 2 — Staff See Which Occupied Rooms Have a Balance Due (Priority: P2)

**Goal**: The room board flags any occupied room whose stay has an outstanding
balance.

**Independent Test**: Add a charge to one occupied room and confirm it's flagged;
confirm a charge-free occupied room is not (spec.md Story 2).

### Tests for User Story 2 ⚠️

- [ ] T030 [P] [US2] Unit test: `RoomService.getBoard()` sets `hasBalanceDue: true`
      for an occupied room with charges, `false` for one without, and leaves it
      absent/null for a non-occupied room — in `RoomServiceTest.java`
- [ ] T031 [P] [US2] Contract test: board response includes `hasBalanceDue` — in
      `RoomControllerTest.java`
- [ ] T032 [P] [US2] `frontend/src/features/room-board/RoomTile.test.tsx`: renders
      a visual balance-due indicator when `hasBalanceDue` is true, not otherwise

### Implementation for User Story 2

- [ ] T033 [US2] `RoomBoardResponse.java`: add `hasBalanceDue` to `RoomBoardEntry`
      (depends on T006, T007)
- [ ] T034 [US2] `RoomService.java`: `getBoard()` bulk-loads charges/authorized
      attempts for every occupied room's booking in one pass each
      (`findByBookingIdIn`) and sets `hasBalanceDue` per room (plan.md Key Design
      Decision #5 — no new dependency on `BookingService`) (depends on T010, T011, T033)
- [ ] T035 [US2] `frontend/src/features/room-board/RoomTile.tsx`: a small visual
      flag (e.g., a badge/icon) when `hasBalanceDue` is true (depends on T016)

**Checkpoint**: The board correctly flags/unflags balance-due rooms as charges are
added. Tests passing, 100% coverage.

---

## Phase 5: User Story 3 — Staff Check Out a Guest With a Balance Due (Priority: P3)

**Goal**: Checkout charges the balance through the payment core; completes only on
approval; a stuck attempt recovers automatically and a late outcome is never
dropped.

**Independent Test**: Check in, add a charge, checkout with an approved card →
checked out, room dirty, only once approval arrives. Checkout with a declined card
→ stays checked in, same balance, retryable. A stuck attempt (no outcome at all)
reverts automatically after the hold-expiry window; a late outcome for it is still
recorded (spec.md Story 3, Clarifications).

### Tests for User Story 3 ⚠️

- [ ] T036 [P] [US3] Unit test: `BookingService.checkOut()` with balance > 0 and a
      card — creates a `PENDING` `CheckoutPaymentAttempt` for exactly the current
      balance, transitions the booking to `CHECKOUT_PENDING` via the same
      conditional-update pattern check-in already uses (never a plain read-then-
      write); with balance > 0 and no card — rejected, nothing created; a second
      checkout attempt while one is already pending — rejected (FR-009) — in
      `BookingServiceTest.java`
- [ ] T037 [P] [US3] Unit test: `BookingService.handlePaymentOutcome()` routes a
      checkout-attempt `transactionId` (not found on any `Booking`) to checkout
      handling — `AUTHORIZED` → attempt `AUTHORIZED`, booking `CHECKED_OUT`, room
      `DIRTY`; `DECLINED`/`FAILED` → attempt `DECLINED`/`FAILED`, booking back to
      `CHECKED_IN`, balance unchanged — in `BookingServiceTest.java`
- [ ] T038 [P] [US3] Unit test: `HoldExpiryService`'s new sweep method reverts a
      `PENDING` `CheckoutPaymentAttempt` older than the configured timeout to
      `EXPIRED` and the booking to `CHECKED_IN` — in
      `services/booking-service/src/test/java/com/creditcard/booking/service/HoldExpiryServiceTest.java`
      (extended, or a focused new test class if that file doesn't already exist —
      verify first)
- [ ] T039 [P] [US3] Unit test: a late `AUTHORIZED` outcome for an `EXPIRED`
      attempt moves it to `LATE_AUTHORIZED` and reduces the booking's balance by
      that attempt's amount (zeroing it out if it was the only charge), logging a
      WARN; a late `DECLINED`/`FAILED` for an `EXPIRED` attempt is a no-op — in
      `BookingServiceTest.java`
- [ ] T040 [P] [US3] Integration test (real CockroachDB + the existing Kafka
      envelope convention): the full saga — check in, add a charge, checkout with
      a card, simulate the `AUTHORIZED` event arriving, confirm `CHECKED_OUT` +
      room `DIRTY`; a second scenario for `DECLINED`; a third for the stuck→swept→
      late-`AUTHORIZED` path — new
      `services/booking-service/src/test/java/com/creditcard/booking/integration/CheckoutPaymentFlowIT.java`
- [ ] T041 [P] [US3] `frontend/src/features/booking/CheckOutCardForm.test.tsx`
- [ ] T042 [P] [US3] `frontend/src/features/room-board/CheckOutAction.test.tsx`
      (extended): balance > 0 renders `CheckOutCardForm` instead of a bare button;
      submitting shows pending, then approved or declined, matching the backend
      outcome
- [ ] T043 [P] [US3] `frontend/src/features/booking/bookingApi.test.ts` (extended):
      the booking-detail polling hook keeps polling while `CHECKOUT_PENDING`, same
      as it already does for `HELD`, and stops once resolved either way

### Implementation for User Story 3

- [ ] T044 [US3] `services/booking-service/src/main/java/com/creditcard/booking/controller/dto/CheckOutRequest.java`
      — optional `card` field (depends on existing `BookingRequest.CardDetails`
      shape, reused)
- [ ] T045 [US3] `BookingService.java`: `checkOut(bookingId, card)` — branches on
      `getBalanceDue` (T024); for balance > 0, atomically moves the booking to
      `CHECKOUT_PENDING` (conditional update, same pattern as `checkIn`), creates
      the `CheckoutPaymentAttempt`, and calls the existing gRPC stub for that exact
      amount (reusing `initiatePayment`'s own `StatusRuntimeException` handling —
      a call failure leaves the attempt with no `transactionId`, swept the same as
      a never-answered one) (depends on T007, T011, T024, T044)
- [ ] T046 [US3] `BookingService.java`: `handlePaymentOutcome()` gains the fallback
      lookup (research.md §5) — on a `Booking.transactionId` miss, try
      `CheckoutPaymentAttemptRepository.findByTransactionId`; route a hit to a new
      `handleCheckoutOutcome(attempt, event)` implementing the state machine in
      data-model.md (depends on T011, T045)
- [ ] T047 [US3] `HoldExpiryService.java`: a second `@Scheduled` method sweeping
      `PENDING` `CheckoutPaymentAttempt`s older than the existing
      `booking.hold-timeout-minutes` value — reusing the same `@Value`, not a new
      configuration key (plan.md Key Design Decision #4) (depends on T011)
- [ ] T048 [US3] `BookingController.java`: `checkOut` accepts an optional
      `CheckOutRequest` body (depends on T044, T045)
- [ ] T049 [US3] `frontend/src/features/booking/bookingApi.ts`: `checkOut` mutation
      accepts an optional card argument; the booking-detail polling hook's stop
      condition generalizes from "not HELD" to "not HELD and not CHECKOUT_PENDING"
      (depends on T027)
- [ ] T050 [US3] `frontend/src/features/booking/CheckOutCardForm.tsx`: card fields,
      reusing `BookingForm`'s own validation conventions (depends on T049)
- [ ] T051 [US3] `frontend/src/features/room-board/CheckOutAction.tsx`: renders
      `CheckOutCardForm` when the booking's balance is greater than zero, the
      existing plain button otherwise (depends on T050)

**Checkpoint — STOP HERE for this turn.** Setup + Foundational + User Story 1 (add
a charge) + User Story 2 (board flag) + User Story 3 (checkout with a balance,
including the recovery sweep) complete: a staff member can check in, add charges,
see the balance flagged on the board, and check out — paying that balance through
the real payment core, with a stuck attempt recovering on its own. Show tests
passing, coverage, and the exact commands to click through add-charge and
checkout-with-balance manually before continuing to Phase 6.

---

## Phase 6: User Story 4 — Staff Check Out a Guest With No Balance Due (Priority: P4)

**Goal**: Confirm the zero-balance checkout path — already implied by Phase 5's own
branching — behaves exactly as before this layer, explicitly and with its own
tests.

**Independent Test**: Check in, add no charges, check out — completes immediately,
no card requested, no payment-core call made (spec.md Story 4).

### Tests for User Story 4 ⚠️

- [ ] T052 [P] [US4] Unit test: `BookingService.checkOut()` with balance == 0 and
      no card — immediate `CHECKED_OUT`, room `DIRTY`, zero interactions with the
      gRPC stub (`verify(transactionServiceBlockingStub, never())...`), no
      `CheckoutPaymentAttempt` created — in `BookingServiceTest.java`
- [ ] T053 [P] [US4] Integration or contract test: a zero-balance checkout with no
      body (or an empty one) returns `200`/`CHECKED_OUT` directly
- [ ] T054 [P] [US4] `frontend/src/features/room-board/CheckOutAction.test.tsx`
      (extended): a zero-balance room renders the plain checkout button, never
      `CheckOutCardForm`

### Implementation for User Story 4

- [ ] T055 [US4] Verify T045's zero-balance branch skips attempt-creation and the
      gRPC call entirely (should already follow from Phase 5's design — this task
      is explicit confirmation + a fix if the tests above find otherwise, not new
      scope)

**Checkpoint**: Both checkout paths — with and without a balance — are
independently verified.

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: The new e2e step, the extended Playwright test, and documentation.

- [ ] T056 [P] `scripts/e2e-test.sh`: add step 15 — check in → add a room-service
      charge → check out with that balance (an authorized card) → `CHECKED_OUT`,
      room `DIRTY` (depends on Phases 3-5's backend work)
- [ ] T057 `frontend/e2e/staff-room-board.spec.ts`: extend the existing smoke test
      with an add-charge + checkout-with-balance stage after check-in, before the
      final `OCCUPIED` assertion is replaced by a `CHECKED_OUT`/`DIRTY` one
- [ ] T058 [P] `README.md`: update the e2e step count/list (14 → 15)
- [ ] T059 [P] `docs/BACKLOG.md`: log refunds, split payments, taxes, and
      invoices/PDFs as explicitly out of scope for this layer
- [ ] T060 Run every scenario in `specs/011-charges/quickstart.md` end to end,
      including the stuck-checkout recovery scenario
- [ ] T061 Verify 100% coverage gates (JaCoCo for `booking-service`, Vitest for
      `frontend/`) and fix any gap
- [ ] T062 Confirm no other service or `k8s/` change was needed — verify only

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately. T001/T002 and T003
  are independent of each other.
- **Foundational (Phase 2)**: Depends on Setup (the migration, T003) — BLOCKS all
  user stories.
- **User Story 1 (Phase 3)**: Depends on Foundational only. **Independently
  demonstrable on its own.**
- **User Story 2 (Phase 4)**: Depends on Foundational; extends `RoomService`/
  `RoomBoardResponse` independently of US1's own files.
- **User Story 3 (Phase 5)**: Depends on Foundational and US1's `getBalanceDue`
  helper (T024, same file extended). **This turn stops after this phase.**
- **User Story 4 (Phase 6)**: Depends on Foundational and US3's `checkOut`
  branching (T045) — this phase is almost entirely verification, not new
  implementation.
- **Polish (Phase 7)**: Depends on all four user stories being complete.

### Within Each User Story

- Tests are written first and confirmed to fail before implementation (Principle II).
- Domain/repository (Foundational) before service logic; service logic before
  controllers/gateway wiring/frontend.

### Parallel Opportunities

- Setup T001-T002 and T003 run in parallel (different files, no shared dependency).
- Foundational T004-T008, T010-T011, T013-T014, T016 (different files) run in
  parallel; their own tests (T017) follow once the files they test exist.
- All test tasks within a story marked [P] run in parallel (different test files).
- User Story 2 can be implemented in parallel with User Story 1 once Foundational
  is done — they touch different files (`RoomService`/`RoomBoardResponse` vs.
  `BookingService`/`BookingController`/`ChargesPanel`) and don't depend on each
  other, only on Phase 2.
- User Story 4 depends on User Story 3's `checkOut` branching existing first, so it
  cannot start in parallel with US3 — but it's almost entirely tests, not new
  implementation, once US3 lands.

---

## Parallel Example: User Story 1

```bash
# Tests for User Story 1, together:
Task: "BookingService.addCharge tests in BookingServiceTest.java"
Task: "POST/GET charges contract tests in BookingControllerTest.java"
Task: "ChargeFlowIT integration test"
Task: "ChargesPanel.test.tsx"
```

---

## Implementation Strategy

### This turn: through the explicit checkpoint

1. Phase 1: Setup (e2e-test.sh root-cause fix, migration)
2. Phase 2: Foundational (blocks everything)
3. Phase 3: User Story 1 (add a charge)
4. Phase 4: User Story 2 (balance-due board flag)
5. Phase 5: User Story 3 (checkout with a balance, including the recovery sweep)
6. **STOP**: tests passing, coverage, the exact commands to click through
   add-charge and checkout-with-balance manually — then wait for the go-ahead
   before Phase 6.

### Afterward: Incremental Delivery

1. Phase 6: User Story 4 (checkout with no balance — verification) → validate
   independently
2. Phase 7: Polish → e2e step 15, extended Playwright test, docs, coverage gate

---

## Notes

- Per CLAUDE.md's per-phase approval convention, each phase above is implemented
  and committed as a whole before stopping — not task by task.
- Avoid: implementing Phase 6/7 work before Phase 5's explicit checkpoint is shown
  and approved.
