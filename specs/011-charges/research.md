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

## 2. Persisting `room_charge_amount` instead of recomputing it

**Decision**: `Booking` gains one new column, `room_charge_amount`, set once inside
`initiatePayment` (the same place `transactionId` is already set) to the exact
amount that call's `InitiateTransactionRequest.amount` was built from.

**Rationale**: FR-004's balance formula needs "the amount already charged at
booking time" as an input. That figure is *currently* always recomputable as
`nights × room.nightlyRate` — but only because nothing today ever changes a room's
`nightlyRate` after a booking exists against it. Persisting the actual historical
figure once, rather than re-deriving it from current, mutable `Room` state every
time balance is calculated, is the only version of FR-004 that's actually correct
regardless of anything a future layer might do to room rates — matching this
spec's own Assumption that the general formula is "kept exact and explicit anyway."

**Alternatives considered**: Recomputing `nights × room.nightlyRate` at balance-
calculation time — rejected: correct today, silently wrong the moment anything
ever changes a room's rate after booking, and the fix (persist the real number) is
one column and zero behavior change today.

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
