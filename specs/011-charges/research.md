# Phase 0 Research: Charges

## 1. Reusing `InitiateTransaction` for the checkout charge — no proto change needed

**Decision**: The checkout charge calls the exact same
`TransactionServiceGrpc.TransactionServiceBlockingStub#initiateTransaction` RPC
`BookingService#initiatePayment` already uses for the original room charge — same
proto, same blocking stub, same 5s deadline, same merchant ID — just a different
amount (the booking's current balance due) and a fresh card.

**Rationale**: Confirmed directly against `proto/v1/transaction_service.proto`:
`InitiateTransactionRequest` already takes `amount` as a plain decimal string with
no assumption baked in about what the charge is *for* — it's already general enough
to represent "the room cost at booking" and "a balance at checkout" as two separate
calls against the same merchant. No new RPC, no new message, no Proto-First review
burden (Principle VII) beyond what already exists.

**Alternatives considered**: A dedicated `ChargeBalance` RPC — rejected, it would be
byte-for-byte identical to `InitiateTransaction` with a different name, violating
the same "don't introduce speculative abstraction" discipline this project applies
everywhere else.

## 2. `room_charge_amount` is a rate lock, set at booking creation — never recomputed from the room's current rate (revised 2026-10-31, tightened same day)

**Decision**: `room_charge_amount` keeps the exact role it already had before
this revision's payment-saga removal — the quoted room total, `nights ×
room.nightlyRate`, computed **once** and persisted — except it is now computed
and set **inside `createConfirmedBooking`** (at the moment the booking is
created) instead of inside the now-removed `initiatePayment`. `getBalanceDue`
reads `booking.getRoomChargeAmount()` directly for the room-cost term — there
is **no fallback to recomputing from `room.nightlyRate` at balance time**. A
booking's room cost is locked in the instant it's confirmed and never changes
again, even if the room's `nightlyRate` changes later.

`transaction_id` (renamed `legacy_room_payment_transaction_id` — plan.md Key
Design Decision #2) keeps its role as a pure legacy marker: non-null if and
only if this booking was created under the pre-revision model and already paid
its room cost at booking time. Nothing sets it going forward. `getBalanceDue`
subtracts `roomChargeAmount` back out of the balance **only when**
`legacyRoomPaymentTransactionId != null` (plan.md Key Design Decision #1) — so
a legacy booking is never double-charged for a room cost it already paid.

**Rationale**: Two independent requirements, both satisfied by the same
mechanism. First, "balance uses the stored amount, never the room's current
rate" — a locked-in quote, not a live join against mutable `Room` state, is
the only way a later rate change can't retroactively move a guest's balance
(a dedicated test proves this directly: change the rate after booking,
confirm the balance is unaffected). Second, the legacy-payment reconciliation
(research.md's original §2 rationale) needs exactly this same persisted,
immutable figure to cancel against — reusing one column for both purposes,
rather than a second "quoted total" column plus the existing legacy figure,
is the simplest shape: every booking, old or new, has exactly one
`room_charge_amount`, meaning exactly "what this stay's room cost was locked in
at," and legacy bookings additionally have a marker saying that figure was
already paid.

Because `createConfirmedBooking` now sets this column for *every* new booking
(not only the old `initiatePayment` path), and every pre-revision row already
had it set by that same old path, there is no longer a real code path that
reaches balance calculation with a null `room_charge_amount` — the
previously-considered "recompute from current rate" fallback isn't a fallback
at all any more, it's a behavior this revision must *not* have. Removing it
entirely (rather than leaving it as unreachable defensive code) is the direct
fix for "balance uses that stored amount, never the room's current rate."

**Alternatives considered**: Recomputing `nights × room.nightlyRate` fresh at
balance-calculation time whenever `room_charge_amount` happens to be unset —
this was the prior (same-day) version of this decision; explicitly rejected
now because it means a room-rate change after booking silently moves an
in-flight guest's balance, which the user flagged as wrong. Adding a new
`is_legacy_room_payment` boolean, backfilled by migration for existing rows —
rejected: strictly redundant with `transaction_id`'s existing non-null-ness.

## 3. `CheckoutPaymentAttempt` as its own table, not a column on `Booking`

**Decision**: A new `checkout_payment_attempts` table, one row per attempt
(`id`, `booking_id`, `transaction_id` nullable, `amount`, `status`, timestamps) —
not a `checkout_transaction_id` column on `Booking`.

**Rationale**: The approved Clarifications design requires finding a *specific past
attempt* by its `transaction_id` when a late Kafka outcome arrives, even after the
booking itself has already reverted to checked-in (FR-012b) — and a booking can
accumulate more than one attempt over its lifetime (declined, then retried; or
timed out, then retried). A single column can only ever hold the *latest* attempt's
transactionId, losing the ability to resolve a late outcome for an *earlier* one
once a second attempt has started. A dedicated table is the only shape that
actually satisfies "never silently drop an AUTHORIZED outcome" once more than one
attempt can exist.

**Alternatives considered**: A single `checkout_transaction_id` column, overwritten
per attempt — rejected for the reason above (loses resolution for superseded
attempts, which the spec explicitly requires to still resolve correctly). A JSON
blob column holding attempt history — rejected: this project queries structured
columns everywhere else (no JSON columns exist in any service's schema today), and
a real table is directly indexable/queryable for the sweep (§4) with no new
convention introduced.

## 4. The checkout-attempt sweep extends `HoldExpiryService`, not a new class

**Decision**: A second `@Scheduled` method on the existing `HoldExpiryService`,
polling `CheckoutPaymentAttemptRepository` for `PENDING` attempts older than the
*same* `booking.hold-timeout-minutes` value (default 10) the hold-expiry sweep
already uses — not a new configuration key, not a new scheduled bean.

**Rationale**: The Clarifications resolution is explicit: reuse the existing
mechanism and window rather than building a second, separately-tuned one. The two
sweeps are mechanically identical (age-based query → bounded per-row handling, each
row's own transition in its own transaction) — splitting them into two classes
would duplicate that shape for no benefit, and configuring them with two separate
timeout values risks exactly the "two things to keep in sync" the Clarifications
answer was explicit about avoiding.

**Alternatives considered**: A new `CheckoutExpiryService` mirroring
`HoldExpiryService`'s shape — rejected per the Clarifications answer itself. A
single generic "expire old state" sweep parameterized over both cases — rejected
as a premature abstraction for exactly two call sites that still read more clearly
as two short, explicit methods on one class than as one generic one.

## 5. Routing a late Kafka outcome to the right handler

**Decision**: `handlePaymentOutcome`'s existing lookup
(`bookingRepository.findByTransactionId`) gets a fallback: on a miss, it tries
`checkoutPaymentAttemptRepository.findByTransactionId` before throwing
`BookingNotFoundForTransactionException`. A hit there routes to new,
checkout-specific handling instead of the existing booking-creation handling.

**Rationale**: Both kinds of outcome arrive on the exact same topic
(`transaction.status.updated`), via the exact same envelope/payload shape and the
exact same `@KafkaListener`. The two transaction IDs are drawn from the same
transaction-service ID space and never collide (each is a fresh UUID minted by a
separate `InitiateTransaction` call), so trying one lookup then the other is
unambiguous and requires no change to the Kafka consumer, the topic, or the event
schema — only to the one method that decides what a given transactionId means.

**Alternatives considered**: A second `@KafkaListener` / consumer group reading the
same topic for checkout outcomes — rejected: doubles the consumer-group
bookkeeping and the idempotency-table traffic for events that are, from Kafka's
perspective, indistinguishable from the ones the existing consumer already reads;
the actual routing decision only needs a repository lookup, not a second listener.

## 6. Balance computed in memory per request, not a stored running total

**Decision**: No `balance_due` column anywhere. Every read (booking detail, room
board) sums `Charge` and `CheckoutPaymentAttempt` rows for the relevant booking(s)
in memory at request time.

**Rationale**: Matches the project's own established precedent for exactly this
kind of read (`RoomService.getBoard()`'s occupant join, research.md §3 from layer
2b) — a plain in-memory join/sum over a handful of rows per stay is simpler and
strictly correct, with no risk of a stored total ever drifting out of sync with
the rows that are supposed to back it. `RoomService` gets this without taking a new
dependency on `BookingService`: both call simple `findByBookingIdIn(...)` queries
on the two new repositories directly and sum in Java, exactly like the existing
occupant-map construction already does.

**Alternatives considered**: A maintained `balance_due` column on `Booking`,
updated transactionally alongside every charge/payment — rejected: real risk of
drift the moment any write path is added later and forgets to maintain it, for a
sum over a handful of rows that costs nothing meaningful to compute on read.

## 7. Checkout's request body: conditionally required card, same endpoint

**Decision**: `POST /api/v1/bookings/{id}/check-out` keeps its existing path and
method; its JSON body gains one optional field, `card`. The service layer rejects
the request if the booking's current balance is greater than zero and `card` is
absent — not a bean-validation `@NotNull`, since "required" here depends on
computed state (the balance), not a fixed shape.

**Rationale**: The spec's own framing is explicit that this is the *same* checkout
action from Stories 3 and 4, differing only by whether a balance exists — not two
different operations. Reusing the one endpoint, with the card truly optional at
the schema level and enforced only by business rule, is the simplest expression of
that and requires no new route, no new gateway matcher beyond what check-out
already has.

**Alternatives considered**: A separate `POST /api/v1/bookings/{id}/checkout-with-
payment` endpoint only for the balance-due case — rejected: splits one business
action into two routes for no reason the spec itself draws a line around, and
would need its own gateway wiring duplicating check-out's existing one.

## 8. Removing only `HELD`, keeping `CANCELLED`/`cancellation_reason` — and migrating stray `HELD` rows, not dropping the column (revised 2026-10-31, second pass)

**Decision**: `BookingStatus.HELD` is removed (nothing can produce it any more
— there is no booking-time payment saga left to hold a room during). `CANCELLED`
and the `cancellation_reason` column/`CancellationReason` enum are **kept**,
unlike the first pass of this revision's plan, which proposed dropping both as
dead code. `CancellationReason` gains one new value, `MIGRATED_UNPAID_HOLD`,
used only by the `V7` migration described below. `V7` no longer drops
`cancellation_reason` — it only adds the guest-detail columns and renames
`transaction_id`. Any pre-existing `HELD` row (there should be none in any real
environment, but the migration must not leave the column with a value the new,
smaller application-level `BookingStatus` enum can't deserialize) is converted
in the same migration: its `booking_room_nights` rows are deleted first (the
room-night slots a never-confirmed hold was reserving must be freed), then its
`status` becomes `CANCELLED` with `cancellation_reason =
'MIGRATED_UNPAID_HOLD'`. A dedicated migration test seeds a `HELD` row plus its
`booking_room_nights` rows against the schema at `V6`, then applies `V7` and
asserts the row is `CANCELLED`/`MIGRATED_UNPAID_HOLD` with its room-night rows
gone.

**Rationale**: Layer 2f's spec draft (not yet formally specified, but already
described to this project) has staff cancel a not-yet-checked-in online
booking — that's a `CANCELLED` status with a reason, the exact mechanism this
revision was about to delete. Keeping it now means 2f adds its own
cancellation trigger and reason value later without having to resurrect a
status/enum this layer had already removed. Converting any stray `HELD` row
rather than leaving it as-is is necessary because the *value* `HELD` is being
removed from the application-level enum — any row still carrying it would fail
to deserialize the moment `BookingStatus.valueOf("HELD")` is attempted; `
CANCELLED`/`MIGRATED_UNPAID_HOLD` is the correct terminal state for a booking
that was, by definition, never actually confirmed. Deleting its
`booking_room_nights` rows in the same step is necessary for the same reason
`cancel()` always deleted them before this revision — a cancelled booking must
never continue occupying a room-night slot.

**Alternatives considered**: Dropping `cancellation_reason` and `CANCELLED`
entirely (this revision's own first-pass decision, superseded here) —
rejected once it became clear layer 2f needs exactly this mechanism; re-adding
it later would mean undoing a deletion one layer after making it. Leaving any
stray `HELD` row alone, unconverted — rejected: `V7` removes `HELD` from the
set of values the application will ever recognize, so an unconverted row
would become unreadable, not merely stale.
