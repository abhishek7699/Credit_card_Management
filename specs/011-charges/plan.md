# Implementation Plan: Charges

**Branch**: `011-charges` | **Date**: 2026-10-31 (revised) | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/011-charges/spec.md`

**Revision note**: This plan was originally written for the first version of this
spec (room cost charged at booking, via `initiatePayment`/`HELD`) and implemented
through Phase 5 under that design. The 2026-10-31 clarification session inverted
that model — no card at booking, immediate confirmation, room cost added to
balance due and collected only at checkout. This revision rewrites the plan for
that new design, treating Phases 1-5 as **rework**, not greenfield. Everything not
called out as changed below (the checkout saga itself, the stuck-attempt sweep,
late-outcome handling, the room-night unique constraint) carries over unchanged
from the original plan.

## Summary

`booking-service` stops charging a booking's room cost at booking time entirely —
no card, no gRPC call, no `HELD` state. A booking is now created and confirmed
synchronously in one request, carrying a much richer guest record (name, phone,
and whatever else the guest offers: email, where they're visiting from, party
size, expected arrival/departure times, notes). Its balance due starts at the full
room cost the instant it's confirmed and only ever decreases as charges are added
and checkout payments are recorded — the room cost is now just another thing
collected at checkout, through the exact same checkout-payment saga this layer
already built for extras (`CHECKED_IN`→`CHECKOUT_PENDING`→`CHECKED_OUT`, the
stuck-attempt sweep, late-`AUTHORIZED` handling). A booking created under the
*old* model, which already paid its room cost at booking time, is reconciled by
treating that historical amount as a payment already received — so it is never
charged for the same room cost twice. `scripts/e2e-test.sh` steps 12-15 and the
Playwright smoke test are rewritten for the new flow.

## Technical Context

**Language/Version**: Java 17 / Spring Boot 3.2 (`booking-service`); TypeScript
5.6 strict / React 18.3 (`frontend/`). Same as the original plan — no new
dependency, no version change.

**Primary Dependencies**: Unchanged. The checkout-saga's use of
`TransactionServiceGrpc`'s `initiateTransaction` RPC is **kept**; the
booking-creation call to that same RPC (`BookingService#initiatePayment`) is
**removed** — the only dependency-shape change is that one call site disappears,
not that any dependency itself changes.

**Storage**: One new Flyway migration (`V7`), additive plus one data-correcting
step — **no column is dropped** — on the existing `bookings` table only; no new
table (the `charges` and `checkout_payment_attempts` tables from the original
`V6` are untouched):
- **Add** eight guest-detail columns: `guest_phone` (NOT NULL), `guest_email`,
  `visiting_from`, `adults` (NOT NULL DEFAULT 1, CHECK >= 1), `children` (NOT NULL
  DEFAULT 0, CHECK >= 0), `expected_arrival_time`, `expected_departure_time`,
  `notes`.
- **Keep** `cancellation_reason` and its column/enum entirely — layer 2f's
  planned staff-cancellation feature needs exactly this mechanism (research.md
  §8). Only `BookingStatus.HELD` is removed from the application-level enum;
  `CANCELLED` stays.
- **Rename** `transaction_id` → `legacy_room_payment_transaction_id` — its role
  inverts from "the current in-flight charge" (there is no such thing for the
  room cost any more) to "a marker: non-null only on a booking created under the
  pre-revision model, meaning its room cost was already paid at booking time."
  Renamed, not dropped-and-recreated, so existing rows keep their value and keep
  meaning exactly what they always meant (a transaction id from the old
  booking-time charge) — just read differently now (see Key Design Decision #1).
- **Convert** any stray `HELD` row: delete its `booking_room_nights` rows, then
  set `status = 'CANCELLED'`, `cancellation_reason = 'MIGRATED_UNPAID_HOLD'` (a
  new `CancellationReason` value) — removing `HELD` from the enum means a row
  still carrying it would otherwise fail to deserialize (research.md §8).

**Testing**: Same conventions as the original plan (JUnit 5 + Mockito +
Testcontainers + JaCoCo 100%; Vitest + RTL + MSW 100% on this layer's files;
`scripts/e2e-test.sh` + Playwright). New this revision: a dedicated test proving
a legacy booking (non-null `legacy_room_payment_transaction_id`) is never
double-charged for its room cost (FR-004's revised formula); a dedicated test
proving a room-rate change after booking never moves an already-confirmed
booking's balance (research.md §2); a dedicated migration test proving a
pre-existing `HELD` row is converted correctly by `V7` (research.md §8).

**Target Platform**: Unchanged — Docker Compose/CI only, no `k8s/` wiring.

**Project Type**: Web application — still purely inside the existing
`booking-service` module, `frontend/` app, and `scripts/e2e-test.sh`. No new
service, no new module.

**Performance Goals**: Unchanged — balance is still a handful of in-memory sums
per request.

**Constraints**: Checkout's card-handling discipline (Principle I) is **unchanged
and unaffected** — the checkout saga this revision keeps is exactly the one the
original plan already satisfied that principle with. Booking creation no longer
touches card data *at all* (stronger than before: there is no card field in that
request any more, not just "transmitted and never stored").

**Scale/Scope**: Same 4→5 user stories (one new story at P1 for guest-detail
capture, the other four renumbered — see spec.md). One revised migration, one
renamed/trimmed domain enum set, one renamed scheduled-sweep class, one
Kafka-outcome-routing simplification (one branch instead of two), four rewritten
e2e steps, one rewritten booking-creation frontend form, one extended room/arrival
panel.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Notes |
|---|---|---|
| I. Tokenization & Zero Raw Card Data | PASS | Booking creation no longer carries a card field at all (stronger than before). Checkout's card handling is unchanged from the original, already-passing design. |
| II. Test-First / 100% Coverage | PASS | Same TDD/100%-coverage convention. The rework explicitly adds a legacy-reconciliation test (FR-004) rather than leaving that branch implicit. |
| III. Idempotent Event Consumers | PASS | `handlePaymentOutcome` gets *simpler*, not more complex — it drops the booking-creation-outcome branch entirely and keeps only the checkout-outcome branch, still through the same `processed_events`/`insertIfAbsent` idempotency mechanism. |
| IV. Iterative One-Step Development | PASS | Treated explicitly as a phase-by-phase rework of the existing Phases 1-5, re-approved per phase, per CLAUDE.md's standing convention — not a silent rewrite. |
| V. Layered Microservice Architecture & Communication Tiers | PASS | Still entirely inside `booking-service`'s existing Controller→Service→Repository layering. One fewer external call (the removed booking-time gRPC call) — no new tier, no new service. |
| VI. Observability & Audit Trail | PASS | The checkout-payment audit trail (every attempt its own row) is unchanged. Booking creation, no longer behind an async payment outcome, is now auditable synchronously — simpler, not thinner. |
| VII. Proto-First Service Contracts | PASS | No `.proto` change, same as the original plan — this revision only removes a call site, it doesn't change what's called. |
| VIII. Frontend Code Sharing — Web + Mobile | Same deviation as layer 2d, unchanged | Not affected by this revision. |

## Project Structure

### Documentation (this feature)

```text
specs/011-charges/
├── plan.md              # This file (revised)
├── research.md          # Phase 0 output (revised)
├── data-model.md        # Phase 1 output (revised)
├── quickstart.md         # Phase 1 output (revised)
├── contracts/
│   └── api.md           # Phase 1 output (revised)
└── tasks.md             # Phase 2 output (/speckit-tasks — rework task list, not yet created)
```

### Source Code (repository root) — changes from the original plan

```text
services/booking-service/
├── src/main/java/com/creditcard/booking/
│   ├── domain/
│   │   ├── Booking.java                        # + 8 guest-detail fields; transactionId
│   │   │                                        #   renamed legacyRoomPaymentTransactionId;
│   │   │                                        #   cancellationReason field KEPT, unchanged
│   │   ├── BookingStatus.java                   # HELD REMOVED only — CONFIRMED/CANCELLED/
│   │   │                                        #   CHECKED_IN/CHECKOUT_PENDING/CHECKED_OUT
│   │   │                                        #   remain (CANCELLED kept for layer 2f)
│   │   ├── CancellationReason.java              # KEPT, + new value MIGRATED_UNPAID_HOLD
│   │   │                                        #   (used only by the V7 migration test's
│   │   │                                        #   assertion / the migration itself)
│   │   ├── Charge.java, ChargeType.java         # unchanged
│   │   ├── CheckoutPaymentAttempt.java          # unchanged
│   │   └── CheckoutAttemptStatus.java           # unchanged
│   ├── repository/
│   │   ├── BookingRepository.java               # findByTransactionId REMOVED (dead —
│   │   │                                        #   nothing looks a booking up by it any
│   │   │                                        #   more); findByStatusAndCreatedAtBefore
│   │   │                                        #   for HELD REMOVED
│   │   ├── ChargeRepository.java, CheckoutPaymentAttemptRepository.java  # unchanged
│   ├── service/
│   │   ├── BookingService.java                  # createHeldBooking renamed
│   │   │                                        #   createConfirmedBooking, confirms
│   │   │                                        #   immediately AND sets roomChargeAmount
│   │   │                                        #   itself (the rate lock, research.md §2);
│   │   │                                        #   initiatePayment REMOVED; cancel() REMOVED
│   │   │                                        #   (no caller left in this layer — layer 2f
│   │   │                                        #   will add its own staff-cancellation
│   │   │                                        #   method when it's built, reusing CANCELLED/
│   │   │                                        #   CancellationReason); getBalanceDue reads
│   │   │                                        #   roomChargeAmount directly (no more
│   │   │                                        #   current-rate fallback) and subtracts it
│   │   │                                        #   back out only when legacy-marked (Key
│   │   │                                        #   Design Decision #1); handlePaymentOutcome
│   │   │                                        #   drops the booking-lookup branch entirely
│   │   │                                        #   — handleRoomChargeOutcome REMOVED
│   │   └── HoldExpiryService.java               # RENAMED CheckoutAttemptSweepService;
│   │                                            #   expireAbandonedHolds REMOVED;
│   │                                            #   expireStuckCheckoutAttempts kept as-is
│   ├── controller/
│   │   ├── BookingController.java               # create() returns BookingDetailResponse
│   │   │                                        #   directly (no more thin
│   │   │                                        #   BookingCreatedResponse); no more
│   │   │                                        #   initiatePayment call
│   │   └── dto/
│   │       ├── BookingRequest.java              # card field REMOVED; +8 guest-detail
│   │       │                                    #   fields (phone required, rest optional)
│   │       ├── CardDetails.java                 # NEW — extracted from BookingRequest's
│   │       │                                    #   former nested type; CheckOutRequest
│   │       │                                    #   now references this directly
│   │       ├── BookingCreatedResponse.java      # DELETED — create() returns the full
│   │       │                                    #   detail response now
│   │       ├── BookingDetailResponse.java       # transactionId REMOVED (internal legacy
│   │       │                                    #   marker only); cancellationReason KEPT,
│   │       │                                    #   unchanged; +8 guest-detail fields
│   │       ├── CheckOutRequest.java             # unchanged (references CardDetails'
│   │       │                                    #   new location)
│   │       ├── ChargeRequest.java, ChargeResponse.java  # unchanged
│   ├── exception/
│   │   └── BookingNotFoundForTransactionException.java  # RENAMED
│   │       UnknownTransactionException.java    # — "Booking" in the old name was
│   │                                            #   misleading once this is only ever
│   │                                            #   thrown for an unrecognized checkout-
│   │                                            #   attempt transactionId, never a booking
│   └── kafka/ (unchanged — same TransactionStatusConsumer, same envelope)
├── src/main/resources/db/migration/
│   └── V7__revise_booking_for_checkout_only_payment.sql   # NEW — see Technical Context
├── src/main/resources/application.yml
│   └── booking.hold-timeout-minutes renamed booking.checkout-timeout-minutes
│       (BOOKING_HOLD_TIMEOUT_MINUTES → BOOKING_CHECKOUT_TIMEOUT_MINUTES) — the old
│       name referred to the now-removed HELD-booking hold; its only remaining
│       meaning is the checkout-attempt sweep window
└── src/test/java/com/creditcard/booking/
    ├── service/BookingServiceTest.java           # remove initiatePayment/cancel/
    │                                              #   handleRoomChargeOutcome/HELD tests;
    │                                              #   add the legacy-reconciliation test
    │                                              #   AND the rate-lock test (change the
    │                                              #   room's nightlyRate after booking,
    │                                              #   assert balance is unaffected)
    ├── service/BookingServiceInitiatePaymentTest.java  # DELETED — tested only the
    │                                              #   now-removed initiatePayment
    ├── service/BookingServiceCheckOutTest.java    # unchanged (checkout's gRPC call is
    │                                              #   kept as-is)
    ├── service/HoldExpiryServiceTest.java         # RENAMED CheckoutAttemptSweepServiceTest;
    │                                              #   drop expireAbandonedHolds tests
    ├── controller/BookingControllerTest.java      # rework create()/checkOut() tests for
    │                                              #   the new request/response shapes
    └── integration/
        ├── BookingConfirmedFlowIT.java, BookingDeclinedFlowIT.java  # DELETED — both
        │   tested the now-removed booking-time payment saga end to end
        ├── BookingV7MigrationIT.java  # NEW — Flyway-targets schema at V6, inserts a HELD
        │   booking + booking_room_nights rows via JDBC, migrates to V7, asserts the row
        │   is CANCELLED/MIGRATED_UNPAID_HOLD with its room-night rows gone (research.md §8)
        ├── CheckInFlowIT.java, CheckOutFlowIT.java, RoomStatusFlowIT.java,
        │   ChargeFlowIT.java, CheckoutPaymentFlowIT.java  # seed data updated for the
        │   new Booking shape (guestPhone now required, no transactionId-as-paid-marker
        │   unless deliberately testing the legacy path)

services/api-gateway/           # no change — same routes/matchers as the original plan

frontend/src/
├── features/booking/
│   ├── bookingApi.ts                            # createBooking's request body drops
│   │                                            #   card, gains the guest-detail fields;
│   │                                            #   its response type becomes Booking
│   │                                            #   directly; poll-stop condition drops
│   │                                            #   the now-impossible 'HELD' check
│   ├── BookingForm.tsx                          # card fields REMOVED; name/phone
│   │                                            #   (required) + email/visiting-from/
│   │                                            #   adults/children/arrival-time/
│   │                                            #   departure-time/notes (optional) added
│   ├── validation.ts                            # BookingFormState no longer extends
│   │                                            #   CardFormState; validateBookingForm no
│   │                                            #   longer calls validateCardFields;
│   │                                            #   CardFormState/validateCardFields stay
│   │                                            #   (CheckOutCardForm still needs them)
│   ├── BookingStatusView.tsx                    # STATUS_LABEL drops only 'HELD'; its
│   │                                            #   'CANCELLED' entry stays (unreachable via
│   │                                            #   this layer's own flow today, but the type
│   │                                            #   still includes it, and layer 2f will
│   │                                            #   reach it)
│   ├── ChargesPanel.tsx, CheckOutCardForm.tsx   # unchanged
│   └── *.test.ts(x)                             # reworked for the above
├── features/room-board/
│   ├── RoomActionPanel.tsx                      # the arriving-booking panel (not just
│   │                                            #   the occupied-room one) now also
│   │                                            #   fetches and shows the full guest
│   │                                            #   record (spec.md FR-023/Clarifications)
│   ├── CheckInAction.tsx                        # extended to show guest details
│   │                                            #   alongside the existing check-in button
│   └── CheckOutAction.tsx, RoomTile.tsx         # unchanged
└── types/staff.ts                               # BookingStatus drops only 'HELD' (keeps
                                                  #   'CANCELLED'); Booking drops transactionId
                                                  #   only (cancellationReason stays), gains
                                                  #   the 8 guest-detail fields;
                                                  #   BookingFormValues drops card, gains the
                                                  #   same 8 fields

frontend/e2e/staff-room-board.spec.ts             # booking step rewritten: no card fields
                                                   #   in the form, fills the new required/
                                                   #   optional guest fields; booking is
                                                   #   confirmed synchronously (no more
                                                   #   "wait for CONFIRMED" poll before
                                                   #   check-in); check-out now always goes
                                                   #   through the card/balance path

scripts/e2e-test.sh
├── step12_booking_confirmed()   # rewritten: no card in the request; 201 response IS the
│                                 #   confirmed booking already — no polling loop needed
├── step13_booking_declined()    # repurposed: "decline at checkout" — check in, attempt
│                                 #   checkout with a declining card, confirm the booking
│                                 #   stays CHECKED_IN with its balance (the room cost)
│                                 #   still due and the room still occupied
├── step14_room_board_lifecycle() # checkout stage now supplies an authorized card and
│                                 #   polls for CHECKED_OUT (room cost alone creates a
│                                 #   balance now — direct zero-balance checkout no longer
│                                 #   applies to an ordinary, non-comped room)
└── step15 (new, Phase 7)         # check in -> add a room-service charge -> check out with
                                  #   a card covering room cost + charge -> CHECKED_OUT,
                                  #   room DIRTY

docs/BACKLOG.md                                   # + whatever this revision still can't
                                                   #   cover (unchanged list: refunds/split
                                                   #   payments/taxes/invoices; email/SMS
                                                   #   confirmation per spec.md Assumptions)
```

**Structure Decision**: Same as the original plan — purely inside the existing
`booking-service` module and `frontend/` app, plus `scripts/e2e-test.sh`. This
revision touches more of each than the original did (it removes a whole saga
rather than only adding one), but introduces no new service, module, or
top-level directory.

## Key Design Decisions

1. **A legacy room-cost payment is reconciled with one conditional term; room
   cost itself is a rate lock set at booking time, never recomputed.**
   `getBalanceDue` reads `booking.roomChargeAmount` directly — `createConfirmed
   Booking` sets it once, to `nights × room.nightlyRate` at the moment of
   booking, and nothing ever recomputes it from the room's (possibly since-
   changed) current rate (research.md §2 — tightened same day after the user
   flagged that a live recompute would let a later rate change move an
   in-flight guest's balance). The existing, previously-unconditional "subtract
   `roomChargeAmount` back out" term becomes conditional on
   `booking.legacyRoomPaymentTransactionId != null` — true only for a booking
   created under the pre-revision model. A brand-new booking's room cost
   simply counts toward the balance, uncancelled — exactly FR-004's new
   formula — while a legacy booking still gets the exact same cancellation the
   original design always gave it, because the renamed column (`transaction_id`
   → `legacyRoomPaymentTransactionId`) keeps the exact value it always had.
   One `if` for the legacy case, one new write (the rate lock) at creation,
   zero new columns.

2. **`transaction_id` is renamed, not dropped and replaced.** Dropping it and
   adding a new boolean/marker column would lose the actual historical
   transaction id for no benefit — the column's *existing* non-null-ness is
   already exactly the right signal (set only by the now-removed
   `initiatePayment`, never by anything this revision adds), and keeping the
   real id around costs nothing and preserves an audit trail back to the
   original charge. Renaming it is purely a naming-accuracy fix (Working
   approach: fix root causes, keep no dead/misleading code) — its *meaning* to
   any code that reads it is unchanged by the rename, only clearer.

3. **`cancellation_reason`/`CANCELLED` are kept, not dropped — only `HELD` is
   removed, and any stray `HELD` row is migrated, not left behind.** The first
   pass of this revision's plan proposed dropping both as dead code, since
   nothing in 2c's own rework produces a cancellation any more. That's reversed
   here: layer 2f's planned staff-cancellation feature needs exactly this
   status and column (research.md §8) — deleting it now would mean 2f has to
   re-add the same mechanism one layer later. Only `HELD` is actually
   unreachable with no future use (it existed purely for an unpaid, in-flight
   booking-time charge; nothing re-adds that concept later), so only `HELD` is
   removed from the enum. Because the enum shrinks, `V7` also converts any
   stray `HELD` row still on disk — deletes its `booking_room_nights` rows,
   then sets it `CANCELLED`/`MIGRATED_UNPAID_HOLD` (a new `CancellationReason`
   value) — so no row is ever left holding a status value the application can
   no longer deserialize. A dedicated migration test proves this conversion.

4. **`HoldExpiryService` is renamed `CheckoutAttemptSweepService`, and its
   `booking.hold-timeout-minutes` config value is renamed
   `booking.checkout-timeout-minutes`.** Once `expireAbandonedHolds` is removed,
   nothing in this class — or in what its one remaining config value controls —
   has anything to do with a "hold" any more; the name would actively mislead
   the next reader into thinking a hold-expiry concept still exists. The
   checkout-attempt sweep itself (the method, its logic, its reuse of one
   `@Value`-configured window rather than a second one) is **completely
   unchanged** by this rename — only the class/config names move to describe
   what's actually still there.

5. **`handlePaymentOutcome` loses a branch, not gains one.** The original
   design's two-branch lookup (booking-by-transactionId, falling back to
   attempt-by-transactionId) existed because *two* kinds of charge shared one
   Kafka topic. Only one kind exists now — every outcome this consumer will
   ever see from this revision forward is a checkout-attempt outcome. The
   booking-lookup branch, `handleRoomChargeOutcome`, and the exception's old
   "Booking" framing (renamed `UnknownTransactionException`, Key Design
   Decision 6 of the original plan is retired) all go — this is a
   simplification the removal pays for directly, not a cost of it.

6. **The frontend's booking-creation flow drops the intermediate "awaiting
   confirmation" view entirely, not just its HELD label.** Because creation is
   now synchronous (the 201 response already *is* the confirmed booking, same
   shape `GET` returns), `BookingForm.tsx` has nothing left to poll for after
   submit — it shows the confirmation directly from the create response.
   `BookingStatusView.tsx`/`useGetBookingQuery`'s polling mechanism is **kept**
   (still needed for the checkout-with-balance flow's `CHECKOUT_PENDING` wait),
   just no longer reached from the creation path at all.

## Complexity Tracking

> No new entries. Layer 2d's existing Constitution Principle VIII deviation is
> unchanged and unaffected by this revision.
