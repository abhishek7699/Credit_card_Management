# Implementation Plan: Charges

**Branch**: `011-charges` | **Date**: 2026-10-30 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/011-charges/spec.md`

## Summary

Adds per-stay charges and a checkout payment to `booking-service`: front-desk staff
record charges (room service/minibar/laundry/other) against a checked-in booking;
the board flags any occupied room with an outstanding balance; checkout charges
that balance through the exact same payment core every other charge in this system
already goes through — mirroring the booking's own HELD→charge→confirmed/cancelled
saga a second time, at the other end of the stay (CHECKED_IN→CHECKOUT_PENDING→
CHECKED_OUT on approval, back to CHECKED_IN on decline, and automatically back to
CHECKED_IN via the existing hold-expiry sweep if nothing is ever heard — with a late
outcome, however late, always recorded as a payment, never dropped). Zero balance
skips the charge entirely. Frontend gets a charges panel on an occupied room, a card
form at checkout, and the payment result — reusing the booking-status polling
pattern layer 2d already built. Also fixes, at the root, the `scripts/e2e-test.sh`
auth-mock cleanup bug the previous layer had to work around in CI.

## Technical Context

**Language/Version**: Java 17 / Spring Boot 3.2 (`booking-service`, additive only);
TypeScript 5.6 strict / React 18.3 (`frontend/`, additive only, same conventions as
layer 2d).

**Primary Dependencies**: Existing only — Spring Data JPA, Spring Kafka, the
existing `TransactionServiceGrpc` blocking stub (no proto change: `InitiateTransaction`
is already generic over amount/merchant, reused as-is for the checkout charge).
RTK Query (frontend, existing `bookingApi.ts`/`roomBoardApi.ts` extended).

**Storage**: One new additive Flyway migration (`V6`) — a `charges` table, a
`checkout_payment_attempts` table, and one new column on `bookings`
(`room_charge_amount`, the amount actually charged at booking time, persisted once
in `initiatePayment` rather than recomputed later from `nights × room.nightlyRate`,
which could drift if a room's rate ever changes after booking — see Key Design
Decision #1). No existing column changed or dropped.

**Testing**: JUnit 5 + Mockito + Testcontainers (CockroachDB) + JaCoCo 100%
instruction/branch coverage (booking-service, same convention as every prior
layer). Vitest + React Testing Library + MSW, 100% coverage on this layer's own new
frontend files (frontend, same convention as layer 2d). `scripts/e2e-test.sh` step
15 (new) + the existing Playwright smoke test extended by one further stage.

**Target Platform**: Same as every prior layer — Docker Compose (local/CI), no new
`k8s/` or GKE wiring (booking-service's own k8s/GKE wiring is already a tracked
backlog item from layer 2a, unchanged here).

**Project Type**: Web application — additive changes inside the existing
`booking-service` module and `frontend/` app; one test-harness correctness fix in
`scripts/e2e-test.sh`. No new service, no new module.

**Performance Goals**: Same as every prior layer — no new throughput requirement;
balance computation is a handful of in-memory sums over a single stay's charges/
payment attempts, same complexity class as the board's existing occupant join.

**Constraints**: A card entered for a checkout charge MUST be transmitted for that
charge only and never stored, matching Principle I exactly as the original booking
charge already does. The checkout-charge recovery sweep MUST reuse the existing
hold-expiry mechanism and window (10 minutes), not a second separately-tuned one
(Clarifications). Exactly one checkout payment attempt may be in flight per booking
at a time.

**Scale/Scope**: 4 user stories, one new domain concept (Charge) and one new
tracked-attempt concept (Checkout Payment Attempt), one booking-service additive
migration, one gateway route/matcher addition, one frontend feature area, one
test-harness bug fix, one new e2e step, one extended Playwright test.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|---|---|---|
| I. Tokenization & Zero Raw Card Data | PASS | The checkout card is forwarded straight to the existing `InitiateTransaction` gRPC call and never held in booking-service's own storage, logs, or the frontend's Redux/browser storage (FR-012) — identical discipline to the original booking charge. |
| II. Test-First / 100% Coverage | PASS | Same TDD/100%-coverage convention as every prior layer, both backend (JaCoCo) and frontend (Vitest, scoped to this layer's own new files — layer 2d's own precedent). |
| III. Idempotent Event Consumers | PASS | The checkout-payment outcome path reuses the *exact same* `processed_events` idempotency table and `insertIfAbsent` pattern `handlePaymentOutcome` already uses for the original charge — no new idempotency mechanism. |
| IV. Iterative One-Step Development | PASS | Implemented and committed phase-by-phase per CLAUDE.md's standing per-phase-approval convention, with an explicit click-through checkpoint after add-charge + checkout-with-balance (per this turn's own instruction). |
| V. Layered Microservice Architecture & Communication Tiers | PASS | Stays inside `booking-service`'s existing Controller→Service→Repository layering; the checkout charge reuses the existing external-REST-to-gRPC tier (no new tier, no new service, no proto change). |
| VI. Observability & Audit Trail | PASS | Every checkout payment attempt is its own persisted row (amount, outcome, timestamps) — a fuller audit trail than the original charge has today, not a thinner one. A late-arriving authorization after a booking has already moved on logs a WARN for manual follow-up, matching the existing late-AUTHORIZED-after-hold-expiry precedent exactly. |
| VII. Proto-First Service Contracts | PASS | No `.proto` change — `InitiateTransactionRequest`/`Response` are already generic enough to reuse as-is for a second charge against the same merchant. |
| VIII. Frontend Code Sharing — Web + Mobile | Same deviation as layer 2d (plan.md's own Complexity Tracking there) | No change to that decision here — nothing in this layer touches it differently. |

## Project Structure

### Documentation (this feature)

```text
specs/011-charges/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md         # Phase 1 output
├── contracts/
│   └── api.md           # Phase 1 output
└── tasks.md             # Phase 2 output (/speckit-tasks — not created by this command)
```

### Source Code (repository root)

```text
services/booking-service/
├── src/main/java/com/creditcard/booking/
│   ├── domain/
│   │   ├── Charge.java                        # NEW — one charge line item
│   │   ├── ChargeType.java                    # NEW — ROOM_SERVICE/MINIBAR/LAUNDRY/OTHER
│   │   ├── CheckoutPaymentAttempt.java        # NEW — one checkout charge attempt
│   │   ├── CheckoutAttemptStatus.java         # NEW — PENDING/AUTHORIZED/DECLINED/FAILED/
│   │   │                                       #   EXPIRED/LATE_AUTHORIZED
│   │   ├── Booking.java                        # + roomChargeAmount column (Key Decision #1)
│   │   └── BookingStatus.java                  # + CHECKOUT_PENDING
│   ├── repository/
│   │   ├── ChargeRepository.java              # NEW
│   │   └── CheckoutPaymentAttemptRepository.java  # NEW
│   ├── service/
│   │   ├── BookingService.java                # + checkout-with-balance saga, balance calc
│   │   └── HoldExpiryService.java              # + checkout-attempt sweep (same @Scheduled job)
│   ├── controller/
│   │   ├── BookingController.java              # check-out gains an optional card body;
│   │   │                                       #   + POST/GET .../charges
│   │   └── dto/
│   │       ├── ChargeRequest.java             # NEW
│   │       ├── ChargeResponse.java            # NEW
│   │       ├── CheckOutRequest.java           # NEW — optional card, required iff balance > 0
│   │       └── BookingDetailResponse.java      # + charges, balanceDue
│   └── kafka/
│       └── (BookingService.handlePaymentOutcome extended, no new consumer class)
├── src/main/resources/db/migration/
│   └── V6__add_charges_and_checkout_payments.sql   # NEW, additive only
└── src/test/java/com/creditcard/booking/            # extended/new unit + integration tests

services/api-gateway/
└── src/main/java/com/creditcard/gateway/config/
    ├── RouteConfig.java        # + booking-charges-get/post routes (reuse booking-service-cb)
    └── SecurityConfig.java     # + GET /api/v1/bookings/* and GET .../charges matchers
                                 #   (FRONT_DESK/HOUSEKEEPING/ADMIN), ahead of the broad rule

frontend/src/
├── features/
│   ├── booking/
│   │   ├── bookingApi.ts                      # + getCharges/addCharge, checkOut gains
│   │   │                                       #   an optional card arg; poll stop condition
│   │   │                                       #   generalized (HELD or CHECKOUT_PENDING)
│   │   ├── ChargesPanel.tsx                    # NEW — add charge + see balance
│   │   ├── CheckOutCardForm.tsx                # NEW — card form, shown only when balance > 0
│   │   └── *.test.ts(x)
│   └── room-board/
│       ├── roomBoardApi.ts (unchanged)         # hasBalanceDue already flows through
│       │                                       #   RoomBoardEntry below
│       ├── CheckOutAction.tsx                  # extended — balance > 0 renders
│       │                                       #   CheckOutCardForm instead of a bare button
│       └── RoomTile.tsx                        # + balance-due visual flag
└── types/staff.ts                              # + Charge, ChargeType, hasBalanceDue on
                                                  #   RoomBoardEntry, charges+balanceDue on Booking

frontend/e2e/staff-room-board.spec.ts            # extended with the charges/checkout-balance stage

scripts/e2e-test.sh                               # root-cause fix: setup_auth_mock detects a
                                                   #   pre-existing container and cleanup()
                                                   #   restores it instead of just deleting it;
                                                   #   + step 15
.github/workflows/ci.yml                          # the "Restart auth-mock" workaround step
                                                   #   (layer 2d) is removed — no longer needed
docs/BACKLOG.md                                   # + whatever this layer still can't cover
                                                   #   (refunds/split payments/taxes/etc.)
```

**Structure Decision**: Purely additive changes inside the existing `booking-service`
module, the existing `frontend/` app, and the existing `scripts/e2e-test.sh` — no new
service, no new frontend area beyond extending layer 2d's own `booking`/`room-board`
features, no new top-level directory.

## Key Design Decisions

1. **Persist the amount actually charged at booking time, don't recompute it
   later.** A new `bookings.room_charge_amount` column is set once, alongside
   `transactionId`, inside the existing `initiatePayment` — the exact figure
   `InitiateTransactionRequest.amount` was built from. Balance due is then always
   `roomChargeAmount + sum(charges) - roomChargeAmount - sum(authorized checkout
   payments)` — the first and third terms cancel today (FR-004's Assumption), but
   persisting the real historical figure means that stays true even if a future
   layer changes how or when the room cost is charged, rather than silently
   drifting if `room.nightlyRate` is ever edited after the fact.

2. **A `CheckoutPaymentAttempt` is its own row, not a column on `Booking`.** A
   booking can accumulate more than one attempt over its lifetime (declined, or
   timed out, then retried) — a single `checkout_transaction_id` column on
   `Booking` couldn't hold that history, and the late-outcome-after-revert
   requirement (FR-012b) specifically needs to find *which* past attempt a late
   Kafka event belongs to, by its own `transaction_id`, even after the booking
   itself has moved on. Mirrors why the booking's own `transaction_id` column
   already works for the *original* charge: there, at most one charge ever
   applies per booking, so one column is correct there and wrong here.

3. **`handlePaymentOutcome`'s existing transactionId lookup gains a second
   branch, not a second consumer.** The same `transaction.status.updated` topic
   carries both kinds of outcome. On a lookup miss against `Booking.transactionId`
   (today's only path), it now also tries
   `CheckoutPaymentAttemptRepository.findByTransactionId` before giving up — if
   that hits, the outcome is routed to new checkout-specific handling instead of
   the booking-creation handling. Reuses the same idempotency table and
   `@KafkaListener` — no new consumer, no new topic.

4. **The checkout-attempt sweep is a second `@Scheduled` method on
   `HoldExpiryService`, not a new class.** It's the same mechanism (a plain
   age-based query, same poll cadence, same `@Value`-configured timeout reused
   rather than a second one) applied to a second case — per the Clarifications
   resolution, deliberately one scheduled job covering both, not two to keep in
   sync. `CheckoutAttemptStatus.EXPIRED` marks a swept attempt; a late outcome for
   an `EXPIRED` attempt moves it to `AUTHORIZED` (recording the payment) or leaves
   it `EXPIRED` (decline/failure — true no-op), mirroring `CancellationReason`'s
   own `HOLD_EXPIRED` → `PAYMENT_AFTER_EXPIRY` precedent exactly.

5. **Balance is computed by summing rows, in memory, the same way the room board
   already joins occupant data — not a stored running total.** `RoomService`
   needs a per-room balance flag for every occupied room on every board request;
   rather than having `RoomService` depend on `BookingService` (introducing a
   cross-service-class coupling that doesn't exist today), both
   `ChargeRepository`/`CheckoutPaymentAttemptRepository` expose simple
   `findByBookingIdIn(...)` queries, and each caller (`BookingService` for one
   booking, `RoomService` for every occupied room on the board at once) sums in
   Java — identical in spirit to `getBoard()`'s existing occupant-map pattern
   (research.md §3, layer 2b).

6. **Checkout's request body becomes conditionally required, not a new
   endpoint.** `POST /api/v1/bookings/{id}/check-out` keeps its existing path; its
   body gains an optional `card` field, validated as required only when the
   booking's current balance is greater than zero — enforced in the service layer
   (not bean validation, since "required" here depends on computed state, not a
   fixed schema rule).

## Complexity Tracking

> No new entries. Layer 2d's existing Constitution Principle VIII deviation
> (`frontend/` stays a flat app, not the `apps/web`/`apps/mobile`/`packages/shared`
> monorepo shape) is unchanged and unaffected by this layer.
