# Tasks: Charges (rework of Phases 1-5 for checkout-only payment)

**Input**: Design documents from `/specs/011-charges/` (plan.md, spec.md, research.md, data-model.md, contracts/api.md, quickstart.md)

**Context**: Phases 1-5 of this layer already shipped the *old* design (room cost
charged at booking time via `initiatePayment`/`HELD`). This is a **rework**, not
greenfield — most tasks modify or delete existing code rather than create new
files. Tasks are grouped to match CLAUDE.md's per-phase approval convention
(Setup / Foundational / each user story / Polish) — implement a whole phase,
commit it, then stop for the checkpoint before the next phase.

## Phase 1: Setup — schema and domain enums

**Purpose**: Land the schema change and the domain-level enum/entity changes
every later phase depends on.

- [X] T001 Create `services/booking-service/src/main/resources/db/migration/V7__revise_booking_for_checkout_only_payment.sql` per data-model.md: add 8 guest-detail columns (`guest_phone` NOT NULL DEFAULT '', `guest_email`, `visiting_from`, `adults` NOT NULL DEFAULT 1 CHECK >= 1, `children` NOT NULL DEFAULT 0 CHECK >= 0, `expected_arrival_time`, `expected_departure_time`, `notes`); rename `transaction_id` → `legacy_room_payment_transaction_id`; delete `booking_room_nights` rows for any `HELD` booking, then `UPDATE bookings SET status = 'CANCELLED', cancellation_reason = 'MIGRATED_UNPAID_HOLD' WHERE status = 'HELD'`. Do **not** drop `cancellation_reason`.
- [X] T002 [P] In `services/booking-service/src/main/java/com/creditcard/booking/domain/BookingStatus.java`, remove `HELD` only; update the Javadoc to describe `CANCELLED` as kept for layer 2f, no longer reachable from this layer's own code.
- [X] T003 [P] In `services/booking-service/src/main/java/com/creditcard/booking/domain/CancellationReason.java`, add `MIGRATED_UNPAID_HOLD`; update the Javadoc to note it's set only by the `V7` migration.
- [X] T004 In `services/booking-service/src/main/java/com/creditcard/booking/domain/Booking.java`: rename the `transactionId` field/column mapping to `legacyRoomPaymentTransactionId` (`@Column(name = "legacy_room_payment_transaction_id")`, getter/setter renamed); update `roomChargeAmount`'s Javadoc (now the rate-locked quote, set by `createConfirmedBooking` itself, never recomputed); add the 8 new fields with JPA column mappings matching T001 (`guestPhone` non-nullable, the other 7 nullable, `adults`/`children` as `int` primitives defaulting via the DB default — not nullable `Integer`, since the column itself is `NOT NULL DEFAULT`); add getters/setters for all 8. `cancellationReason` field is unchanged.
- [X] T005 [P] In `services/booking-service/src/main/java/com/creditcard/booking/domain/BookingTest.java`, update/extend the existing domain test to cover the new fields and the renamed `legacyRoomPaymentTransactionId` accessor.

**Checkpoint**: Project compiles against the new schema/entity shape (service/controller layers not yet updated — expect compile errors downstream until Phase 2/3 land; this phase's own test, T005, passes in isolation).

---

## Phase 2: Foundational — shared repository/exception/config renames

**Purpose**: Infrastructure every user story's rework depends on.

- [X] T006 In `services/booking-service/src/main/java/com/creditcard/booking/repository/BookingRepository.java`: remove `findByTransactionId` (dead — nothing looks a booking up by it any more) and `findByStatusAndCreatedAtBefore` (only ever called for `HELD`, which no longer exists).
- [X] T007 Rename `services/booking-service/src/main/java/com/creditcard/booking/kafka/BookingNotFoundForTransactionException.java` → `UnknownTransactionException.java` (same package); update its Javadoc to describe it as thrown only for an unrecognized checkout-attempt transactionId, never a booking.
- [X] T008 Rename `services/booking-service/src/main/java/com/creditcard/booking/service/HoldExpiryService.java` → `CheckoutAttemptSweepService.java`: remove `expireAbandonedHolds()` entirely; keep `expireStuckCheckoutAttempts()` unchanged; rename the constructor's `@Value("${booking.hold-timeout-minutes:10}")` to `@Value("${booking.checkout-timeout-minutes:10}")` and the `holdTimeoutMinutes` field to `checkoutTimeoutMinutes`; drop the now-unused `BookingRepository`/`bookingService.cancel` dependency if `BookingRepository` is no longer referenced after removing `expireAbandonedHolds`.
- [X] T009 [P] In `services/booking-service/src/main/resources/application.yml`, rename `booking.hold-timeout-minutes` → `booking.checkout-timeout-minutes` (keep the `${BOOKING_HOLD_TIMEOUT_MINUTES:10}` env-var fallback name as `${BOOKING_CHECKOUT_TIMEOUT_MINUTES:10}` — confirmed via `docker-compose.yml` that this env var is never explicitly set there, so no docker-compose change is needed, only the default).
- [X] T010 Rename `services/booking-service/src/test/java/com/creditcard/booking/service/HoldExpiryServiceTest.java` → `CheckoutAttemptSweepServiceTest.java`: remove its `expireAbandonedHolds` test cases; keep `expireStuckCheckoutAttempts` test cases, updated for the renamed class/field.
- [X] T011 Delete `services/booking-service/src/test/java/com/creditcard/booking/integration/HoldExpiryFlowIT.java` if it only exercises `expireAbandonedHolds`; if it also covers `expireStuckCheckoutAttempts`, keep it but remove only the hold-expiry-specific scenarios and rename references to `CheckoutAttemptSweepService`.

**Checkpoint**: Repository/exception/config renames compile and their own unit tests pass; `BookingService`/`BookingController` still reference the old names (updated next phase).

---

## Phase 3: User Story 1 — Capture a full guest record and confirm immediately (Priority: P1) 🎯 MVP

**Goal**: `POST /api/v1/bookings` takes no card, confirms synchronously, records the full guest record, and locks in the room cost as the booking's starting balance.

**Independent Test**: Submit a booking with only name/phone/dates — confirm immediate `CONFIRMED` status, no card requested, no payment-core call, `balanceDue` equal to the room's cost for those nights. Submit one with every optional field and confirm all of it round-trips.

### Implementation for User Story 1

- [X] T012 [US1] In `services/booking-service/src/main/java/com/creditcard/booking/controller/dto/BookingRequest.java`: remove the `card` field and the nested `CardDetails` record; add `@NotBlank String guestPhone`, `String guestEmail`, `String visitingFrom`, `@Min(1) Integer adults`, `@Min(0) Integer children`, `LocalTime expectedArrivalTime`, `LocalTime expectedDepartureTime`, `String notes`.
- [X] T013 [P] [US1] Create `services/booking-service/src/main/java/com/creditcard/booking/controller/dto/CardDetails.java` as a top-level record with the same 4 fields `BookingRequest.CardDetails` had (`cardNumber`, `expiry`, `cvv`, `cardholderName`), same validation annotations.
- [X] T014 [US1] Update `services/booking-service/src/main/java/com/creditcard/booking/controller/dto/CheckOutRequest.java` to reference the new top-level `CardDetails` instead of `BookingRequest.CardDetails`.
- [X] T015 [US1] In `services/booking-service/src/main/java/com/creditcard/booking/service/BookingService.java`: rename `createHeldBooking` → `createConfirmedBooking`; set `status` directly to `CONFIRMED` (no `HELD`); populate the 8 new guest-detail fields from the request (defaulting `adults`/`children` to 1/0 when the request's values are null — FR-018); compute `nights × room.nightlyRate` and set it on `booking.roomChargeAmount` before saving (the rate lock, research.md §2) — this now happens here, not in the removed `initiatePayment`; remove `initiatePayment` entirely; remove `cancel()` (no caller remains in this layer); update class-level Javadoc (no longer "HELD -> charge -> CONFIRMED/CANCELLED saga").
- [X] T016 [US1] In the same file, update `getBalanceDue`/`resolveRoomChargeAmount`: `resolveRoomChargeAmount` now simply returns `booking.getRoomChargeAmount()` (no `Room` lookup, no current-rate fallback — research.md §2); the legacy-cancellation term in `getBalanceDue` becomes conditional on `booking.getLegacyRoomPaymentTransactionId() != null` (plan.md Key Design Decision #1) instead of always subtracting; update the method's Javadoc accordingly.
- [X] T017 [US1] In the same file, simplify `handlePaymentOutcome`: remove the `bookingRepository.findByTransactionId` branch and `handleRoomChargeOutcome` entirely — go straight to the `CheckoutPaymentAttemptRepository` lookup, throwing the renamed `UnknownTransactionException` on a miss; update the class/method Javadoc (drop the HELD+AUTHORIZED/DECLINED matrix description, since booking creation no longer goes through this consumer at all).
- [X] T018 [US1] In `services/booking-service/src/main/java/com/creditcard/booking/controller/BookingController.java`: `create()` calls `bookingService.createConfirmedBooking(request)` and returns `toDetailResponse(booking)` directly (no more `initiatePayment` call, no more `BookingCreatedResponse`); update its Javadoc (the response now reflects the already-confirmed booking, not a HELD one awaiting payment).
- [X] T019 [P] [US1] Delete `services/booking-service/src/main/java/com/creditcard/booking/controller/dto/BookingCreatedResponse.java` (no longer used).
- [X] T020 [US1] In `services/booking-service/src/main/java/com/creditcard/booking/controller/dto/BookingDetailResponse.java`: remove the `transactionId` field (internal legacy marker only); keep `cancellationReason` unchanged; add the 8 guest-detail fields; update `BookingController#toDetailResponse` to match the new constructor shape.
- [X] T021 [US1] In `services/booking-service/src/main/java/com/creditcard/booking/repository/BookingRepository.java`, if not already covered by T006, confirm no remaining reference to `findByTransactionId`/HELD-based queries; add nothing new (the room-night unique-constraint path is unchanged).
- [X] T022 [P] [US1] In `services/booking-service/src/test/java/com/creditcard/booking/service/BookingServiceTest.java`: remove tests for `initiatePayment`/`cancel`/`handleRoomChargeOutcome`/HELD transitions; add tests for acceptance scenarios 1-6 (spec.md US1): minimal-field booking confirms immediately with `roomChargeAmount` set and no payment-core interaction; full-field booking round-trips every guest field; missing required field rejected; omitted adults/children default to 1/0; `getBalanceDue` reduces to `charges - checkoutPayments` for a seeded legacy booking (non-null `legacyRoomPaymentTransactionId`) — the double-charge test the user explicitly asked for; a room-rate change after booking does not change an existing booking's balance — the rate-lock test the user explicitly asked for.
- [X] T023 [P] [US1] Delete `services/booking-service/src/test/java/com/creditcard/booking/service/BookingServiceInitiatePaymentTest.java` (tested only the removed `initiatePayment`).
- [X] T024 [P] [US1] Delete `services/booking-service/src/test/java/com/creditcard/booking/integration/BookingConfirmedFlowIT.java` and `BookingDeclinedFlowIT.java` (both tested the removed booking-time payment saga end to end).
- [X] T025 [US1] Create `services/booking-service/src/test/java/com/creditcard/booking/integration/BookingV7MigrationIT.java`: using the existing Testcontainers CockroachDB setup (`support/CockroachTestContainer.java`), run Flyway targeted at `V6`, insert a `HELD` booking row plus `booking_room_nights` rows directly via JDBC, migrate to `V7`, assert the row is `CANCELLED`/`MIGRATED_UNPAID_HOLD` and its `booking_room_nights` rows are gone (research.md §8).
- [X] T026 [US1] Update `services/booking-service/src/test/java/com/creditcard/booking/controller/BookingControllerTest.java`: rework `create()` tests for the new request (no card, guest fields) and response shape (full detail, `CONFIRMED` immediately, no polling assumption).
- [X] T027 [P] [US1] Update `services/booking-service/src/test/java/com/creditcard/booking/service/BookingServiceConcurrencyTest.java` and any other test seeding a `Booking` directly (`CheckInFlowIT`, `CheckOutFlowIT`, `RoomStatusFlowIT`, `ChargeFlowIT`, `CheckoutPaymentFlowIT`) to supply the now-required `guestPhone` and to call `createConfirmedBooking` instead of `createHeldBooking`/`initiatePayment`.

**Checkpoint**: `POST /api/v1/bookings` confirms synchronously with the full guest record; `./gradlew :booking-service:test` green; this is the MVP — everything below depends on it.

---

## Phase 4: User Story 2 — Add a charge during a stay (Priority: P2)

**Goal**: Unaffected by the payment-model inversion — verify it still works against the new `Booking` shape.

**Independent Test**: Check a booking in (via US1's new creation flow), add charges of different types, confirm each appears as a line item with balance updated.

- [X] T028 [US2] Review `addCharge`/`getCharges` in `BookingService.java` — confirm no change needed (FR-001/FR-002/FR-003 are independent of the payment-model rework).
- [X] T029 [P] [US2] Update any `ChargeFlowIT.java` seed data to use `createConfirmedBooking` with a `guestPhone` (same mechanical update as T027, scoped here if not already done there).

**Checkpoint**: Charge-adding acceptance scenarios (spec.md US2, 1-3) still pass unchanged.

---

## Phase 5: User Story 3 — Occupied rooms with a balance due flagged on the board (Priority: P3)

**Goal**: Unaffected by the payment-model inversion, but now routinely true for *every* occupied room, not just ones with extras — verify the board reflects this.

**Independent Test**: Check a booking in with no charges added — confirm the board flags it as having a balance due (room cost alone is now enough, the central behavior change of this revision).

- [X] T030 [US3] ~~Review~~ **Fixed during Phase 3**: `RoomService.hasBalanceDueByBookingId` turned out to be a *separate* bulk computation from `BookingService.getBalanceDue` (charges minus checkout payments only, no room cost at all) — correct under the old model only because room cost always cancelled unconditionally, so this was silently hiding the real behavior change until read directly. Fixed to apply the same room-cost-plus-legacy-adjustment formula, batched over the already-loaded `Booking` objects (no N+1 introduced). See the Phase 3 commit.
- [X] T031 [P] [US3] Covered by `RoomServiceTest`'s `getBoard_occupiedRoomNoCharges_roomCostAloneCreatesBalanceDue_hasBalanceDueTrue` (added during the Phase 3 fix above) plus `getBoard_occupiedLegacyBookingNoCharges_roomCostAlreadyPaidAtBooking_hasBalanceDueFalse` for the legacy case.

**Checkpoint**: Board balance-due flag matches spec.md US3's 2 acceptance scenarios, including the new "room cost alone is enough" case.

---

## Phase 6: User Story 4 — Check out with a balance due (Priority: P4)

**Goal**: The checkout saga itself is explicitly kept unchanged — verify it still works now that it's reached far more often (room cost alone creates a balance).

**Independent Test**: Check in, attempt checkout with an approved card, confirm `CHECKED_OUT`/`DIRTY` only after approval; separately, attempt checkout with a declining card and confirm the booking **stays `CHECKED_IN`** with the same balance (this is the scenario e2e step 13 is repurposed to, Phase 8).

- [X] T032 [US4] Review `checkOut`/`handleCheckoutOutcome`/`expireCheckoutAttempt` in `BookingService.java` — confirm no change needed beyond what Phase 2/3 already touched (the saga, sweep, and late-`AUTHORIZED` handling are explicitly kept as-is).
- [X] T033 [P] [US4] Confirm `BookingServiceCheckOutTest.java` and `CheckOutFlowIT.java`/`CheckoutPaymentFlowIT.java` still pass once their seed data uses `createConfirmedBooking` (T027) — no new test cases required, spec.md US4's 8 acceptance scenarios were already covered by the original Phase 3/4 tests.

**Checkpoint**: All 8 of spec.md US4's acceptance scenarios pass against the reworked booking-creation flow.

---

## Phase 7: User Story 5 — Check out with no balance due (Priority: P5, now a narrow edge case)

**Goal**: Confirm the zero-balance path (now reachable only via a zero-rate room) still works.

**Independent Test**: Check in a booking for a zero-nightly-rate room, add no charges, check out — confirm immediate `CHECKED_OUT`/`DIRTY`, no card, no payment-core call.

- [X] T034 [US5] Add/update a test (`BookingServiceCheckOutTest.java` or `CheckOutFlowIT.java`) seeding a room with `nightlyRate = 0.00`, confirming `getBalanceDue` is zero immediately after `createConfirmedBooking` and checkout completes with no card.

**Checkpoint**: spec.md US5's single acceptance scenario passes; zero-balance checkout is confirmed reachable only through this narrow path.

---

## Phase 8: Polish — e2e, Playwright, frontend, docs

**Purpose**: Cross-cutting changes spanning the whole feature, done last per CLAUDE.md's phase convention.

### Backend polish

- [X] T035 [P] Search the whole `booking-service` module for any remaining reference to `HELD`, `initiatePayment`, `createHeldBooking`, `BookingCreatedResponse`, `handleRoomChargeOutcome`, or `BookingNotFoundForTransactionException` and fix/remove each (no dead code left, per the user's standing instruction).
- [X] T036 [P] Confirm JaCoCo 100% coverage still holds for `booking-service` after all deletions/additions (`./gradlew :booking-service:jacocoTestCoverageVerification`).

### e2e and Playwright

- [X] T037 Rewrite `step12_booking_confirmed()` in `scripts/e2e-test.sh`: book with no card field in the request body; assert the `201` response already has `"status": "CONFIRMED"` — remove the polling loop entirely.
- [X] T038 Repurpose `step13_booking_declined()` in `scripts/e2e-test.sh` to "decline at checkout": book (no card) → confirmed → check in → add a charge (optional, for a non-trivial balance) → attempt checkout with a card the payment core declines → assert the booking stays `CHECKED_IN` with its balance unchanged and the room stays `OCCUPIED`; rename the function if the script's step-naming convention expects it (e.g. `step13_checkout_declined`).
- [X] T039 Update `step14_room_board_lifecycle()` in `scripts/e2e-test.sh`: book with no card (already `CONFIRMED`), check in, assert board shows `OCCUPIED` + `hasBalanceDue: true`; check out **with** an authorized card this time (room cost alone creates a balance now); poll `GET /api/v1/bookings/{id}` for `CHECKED_OUT` via the `CHECKOUT_PENDING` path instead of expecting a direct zero-balance checkout; assert board shows `DIRTY`, then `VACANT_CLEAN` after housekeeping cleans.
- [X] T040 Add a new `step15_checkout_with_charge()` to `scripts/e2e-test.sh`: book (no card) → confirmed → check in → add a room-service charge → check out with a card covering room cost + charge → assert `CHECKED_OUT`, room `DIRTY`. Wire it into the script's step-list/summary output alongside steps 1-14.
- [X] T041 Update `frontend/e2e/staff-room-board.spec.ts` (Playwright): the booking step fills no card fields, only the new required/optional guest fields; remove any "wait for CONFIRMED" poll before check-in (creation is synchronous now); the check-out step now always exercises the card/balance path.

### Frontend

- [X] T042 [P] In `frontend/src/types/staff.ts`: `BookingStatus` drops only `'HELD'` (keeps `'CANCELLED'`); `Booking` drops `transactionId` (keeps `cancellationReason`), gains the 8 guest-detail fields; `BookingFormValues` drops `card`, gains the same 8 fields.
- [X] T043 In `frontend/src/features/booking/validation.ts`: `BookingFormState` no longer extends `CardFormState`; `validateBookingForm` no longer calls `validateCardFields`; add validation for the new required (`guestName`, `guestPhone`, `checkIn`, `checkOut`) and optional fields. `CardFormState`/`validateCardFields` themselves are unchanged (still used by `CheckOutCardForm.tsx`).
- [X] T044 In `frontend/src/features/booking/BookingForm.tsx`: remove card fields; add inputs for `guestPhone` (required), `guestEmail`, `visitingFrom`, `adults`, `children`, `expectedArrivalTime`, `expectedDepartureTime`, `notes` (optional).
- [X] T045 In `frontend/src/features/booking/bookingApi.ts`: `createBooking`'s request body drops `card`, gains the guest-detail fields; its success response is used directly as the confirmed `Booking` (no more "thin create response" type); `useGetBookingQuery`'s poll-stop condition drops the now-impossible `'HELD'` check.
- [X] T046 [P] In `frontend/src/features/booking/BookingStatusView.tsx`: remove the `STATUS_LABEL` entry for `'HELD'`; keep the `'CANCELLED'` entry (still a valid, if currently unreachable, status).
- [X] T047 In `frontend/src/features/room-board/RoomActionPanel.tsx` and `CheckInAction.tsx`: extend both the arriving-booking panel and the occupied-room panel to show the full guest-detail record (FR-023, Clarifications: "applies to both").
- [X] T048 [P] Update all affected `*.test.ts(x)` files under `frontend/src/features/booking/` and `frontend/src/features/room-board/` for the above; confirm Vitest/RTL/MSW 100% coverage still holds for this layer's files.

### Docs

- [X] T049 [P] Update `docs/BACKLOG.md` per spec.md's Assumptions (no refunds/split payments/taxes/discounts/invoices; no email/SMS confirmation sent for a new booking) if not already logged there from the original Phase 1-5 pass.

**Checkpoint**: Full stack (`docker compose up -d --build` + `./scripts/e2e-test.sh` + Playwright) green end to end; ready for PR (per CLAUDE.md: do not merge without explicit approval).

---

## Dependencies & Execution Order

- **Setup (Phase 1)**: No dependencies — can start immediately. Blocks everything else (schema/entity shape).
- **Foundational (Phase 2)**: Depends on Phase 1. Blocks Phase 3 (BookingService's rework touches the renamed exception/repository methods).
- **User Story 1 (Phase 3)**: Depends on Phase 1-2. This is the actual rework's core — every other story phase below depends on it compiling and passing, since booking creation is how every later story gets a booking to work with. Not independently skippable the way the spec's own "priority" framing might otherwise suggest, because this is a rework of shared machinery, not additive feature work.
- **User Stories 2-5 (Phases 4-7)**: Each depends on Phase 3; each is primarily a *verification* pass (the saga/charges/board code itself is explicitly unchanged) rather than new implementation — they can be done in any order relative to each other, though the spec's own priority order (P2→P5) is the natural one.
- **Polish (Phase 8)**: Depends on all of Phases 1-7 (e2e steps exercise the full lifecycle; frontend changes depend on the final API shape).

## Implementation Strategy

Phase-by-phase with a checkpoint after each, per CLAUDE.md's approval granularity
(approve per phase, not per task). Suggested order: 1 → 2 → 3 (MVP — this is where
nearly all the actual rework lives) → 4 → 5 → 6 → 7 (quick verification passes) →
8 (e2e/Playwright/frontend/docs, done once the backend is stable). Commit at each
phase checkpoint; stop and show the checkpoint before moving to the next phase.
