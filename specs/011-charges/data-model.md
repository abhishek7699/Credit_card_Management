# Phase 1 Data Model: Charges

Extends `booking-service`'s existing schema (`booking_service`). One new migration
(`V7`) on `bookings` — additive (guest-detail columns), a rename
(`transaction_id` → `legacy_room_payment_transaction_id`), and a one-time data
correction (any stray `HELD` row converted to `CANCELLED`); no column is
dropped — plus the two tables `charges` and `checkout_payment_attempts` already
added by the prior version of
this layer (unaffected by this revision).

## `Booking` *(revised)*

| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | unchanged |
| `room_id` | UUID, NOT NULL, FK → `rooms (id)` | unchanged |
| `guest_name` | VARCHAR(200), NOT NULL | unchanged |
| `guest_phone` | VARCHAR(30), NOT NULL *(new)* | required (Clarifications: required-field bundle) |
| `guest_email` | VARCHAR(320), nullable *(new)* | optional |
| `visiting_from` | VARCHAR(200), nullable *(new)* | optional, free text |
| `adults` | INT4, NOT NULL, DEFAULT 1, CHECK (adults >= 1) *(new)* | defaults to 1 when omitted (Clarifications) |
| `children` | INT4, NOT NULL, DEFAULT 0, CHECK (children >= 0) *(new)* | defaults to 0 when omitted (Clarifications) |
| `check_in` | DATE, NOT NULL | unchanged |
| `check_out` | DATE, NOT NULL | unchanged |
| `expected_arrival_time` | TIME, nullable *(new)* | optional, informational only (Assumptions) |
| `expected_departure_time` | TIME, nullable *(new)* | optional, informational only (Assumptions) |
| `notes` | VARCHAR(2000), nullable *(new)* | optional, free text; never logged (FR-022) |
| `status` | VARCHAR(20), NOT NULL | `HELD` **removed** from the enum; `CANCELLED` **kept** (layer 2f will add staff cancellation) — see `BookingStatus` below |
| `legacy_room_payment_transaction_id` | VARCHAR(100), nullable *(renamed from `transaction_id`)* | non-null **only** on a booking created under the pre-revision model, whose room cost was already charged at booking time; never set by anything in this revision (research.md §2, revised) |
| `room_charge_amount` | DECIMAL(10,2), NOT NULL once set *(unchanged column, revised meaning)* | the rate-locked quote — `nights × room.nightlyRate` at the moment the booking was created — set by `createConfirmedBooking` itself now, not only by the old `initiatePayment`. Balance calculation reads this value directly and **never** recomputes from the room's current rate (research.md §2) |
| `created_at` / `updated_at` | TIMESTAMPTZ, NOT NULL | unchanged |

`cancellation_reason` — **kept, unchanged column**. Still nullable; still null
for every booking this layer's own code creates. `CancellationReason` gains one
new value, `MIGRATED_UNPAID_HOLD` (below) — its four pre-existing values
(`PAYMENT_DECLINED`, `PAYMENT_FAILED`, `HOLD_EXPIRED`, `PAYMENT_AFTER_EXPIRY`)
are kept as-is for layer 2f to extend, not removed even though nothing in this
layer's own flow produces them any more (research.md §8).

### Migration `V7__revise_booking_for_checkout_only_payment.sql`

```sql
ALTER TABLE bookings
    ADD COLUMN guest_phone VARCHAR(30) NOT NULL DEFAULT '',
    ADD COLUMN guest_email VARCHAR(320),
    ADD COLUMN visiting_from VARCHAR(200),
    ADD COLUMN adults INT4 NOT NULL DEFAULT 1 CHECK (adults >= 1),
    ADD COLUMN children INT4 NOT NULL DEFAULT 0 CHECK (children >= 0),
    ADD COLUMN expected_arrival_time TIME,
    ADD COLUMN expected_departure_time TIME,
    ADD COLUMN notes VARCHAR(2000);

ALTER TABLE bookings RENAME COLUMN transaction_id TO legacy_room_payment_transaction_id;

-- Free the room-night slots any never-confirmed hold was reserving, then
-- convert the row itself so no booking is left carrying a status value the
-- (now smaller) application-level BookingStatus enum no longer recognizes.
DELETE FROM booking_room_nights
WHERE booking_id IN (SELECT id FROM bookings WHERE status = 'HELD');

UPDATE bookings
SET status = 'CANCELLED', cancellation_reason = 'MIGRATED_UNPAID_HOLD'
WHERE status = 'HELD';
```

`guest_phone`'s `DEFAULT ''` exists only so the `ADD COLUMN ... NOT NULL` succeeds
against any pre-existing rows (there are none in any environment that matters —
this layer hasn't shipped past Phase 5 anywhere outside this branch — but the
default costs nothing and avoids a two-step add-then-backfill-then-constrain
dance). No application code ever relies on the empty-string default; every row
created from this revision forward supplies a real phone number (FR-017/FR-019).

This migration adds and renames columns and corrects stray `HELD` rows — it
does **not** drop any column (research.md §8 — a reversal of this revision's
first pass, which had proposed dropping `cancellation_reason`). A dedicated
migration test (`BookingV7MigrationIT` or similar) applies schema only through
`V6`, inserts a `HELD` booking plus its `booking_room_nights` rows directly via
JDBC, applies `V7`, and asserts: `status = 'CANCELLED'`,
`cancellation_reason = 'MIGRATED_UNPAID_HOLD'`, and zero remaining
`booking_room_nights` rows for that booking.

### `BookingStatus` (state machine, revised)

```
   createConfirmedBooking()
          │
          ▼
      CONFIRMED ──► CHECKED_IN ──┬─► CHECKOUT_PENDING ──► CHECKED_OUT   (terminal)
                                 │         │
                                 │         └──(declined/failed/expired)──┐
                                 │                                        │
                                 └────────────◄───────────────────────────┘
                     (zero balance: CHECKED_IN ──► CHECKED_OUT directly, no pending step)

      CANCELLED   (terminal — reachable only via the V7 migration's one-time
                    HELD conversion, or a pre-revision historical row; layer 2f
                    will add a new, staff-initiated path into this status)
```

`HELD` is **removed from the enum** — it existed only for the booking-time
payment saga (an unpaid hold awaiting a charge outcome) this revision removes.
A booking is `CONFIRMED` the instant `createConfirmedBooking` returns; nothing
can prevent that except a room/date conflict or a missing required field, both
rejected synchronously, before any row is written (FR-019/FR-020) — there is no
intermediate state to roll back from. `CANCELLED` is **kept** (research.md §8)
— this layer's own code never produces it, but the value, its column, and its
reason enum remain for layer 2f's staff-cancellation feature to build on.

Everything below `CONFIRMED` (the `CHECKED_IN`→`CHECKOUT_PENDING`→`CHECKED_OUT`
checkout saga, its sweep, and late-outcome handling) is **completely unchanged**
from the prior version of this layer.

## `Charge` *(unchanged — table `charges`)*

| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `booking_id` | UUID, NOT NULL, `REFERENCES bookings (id)` | |
| `type` | VARCHAR(20), NOT NULL | `ROOM_SERVICE` \| `MINIBAR` \| `LAUNDRY` \| `OTHER` |
| `description` | VARCHAR(500), NOT NULL | free text |
| `amount` | DECIMAL(10,2), NOT NULL | |
| `created_at` | TIMESTAMPTZ, NOT NULL | ordering for FR-003's "in the order they were added" |

Add-only — no update or delete path exists anywhere in this layer.

## `CheckoutPaymentAttempt` *(unchanged — table `checkout_payment_attempts`)*

| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `booking_id` | UUID, NOT NULL, `REFERENCES bookings (id)` | |
| `transaction_id` | VARCHAR(100), nullable | set once the gRPC call returns a transaction id |
| `amount` | DECIMAL(10,2), NOT NULL | the balance due at the moment this attempt was created |
| `status` | VARCHAR(20), NOT NULL | see below |
| `created_at` / `updated_at` | TIMESTAMPTZ, NOT NULL | |

### `CheckoutAttemptStatus` (state machine, unchanged)

```
  PENDING ──► AUTHORIZED           (approved — booking checks out, FR-010)
     │
     ├──► DECLINED | FAILED        (booking returns to CHECKED_IN, FR-011)
     │
     └──► EXPIRED ──► LATE_AUTHORIZED   (late approval recorded as a payment, FR-012b)
              │
              └─► (stays EXPIRED)       (late decline/failure — true no-op)
```

Entirely unchanged from the prior version — the checkout saga, its sweep
(`CheckoutAttemptSweepService`, renamed from `HoldExpiryService`, plan.md Key
Design Decision #4), and late-`AUTHORIZED` handling are explicitly kept as-is.

## Balance due (computed, not persisted — revised)

```
roomCost(booking) = booking.roomChargeAmount
    -- the rate-locked quote set at createConfirmedBooking (research.md §2).
    -- Always non-null by the time balance is calculated: every booking,
    -- old or new, has it set. NEVER recomputed from room.nightlyRate here.

legacyPaidAmount(booking) =
    booking.legacyRoomPaymentTransactionId != null ? roomCost(booking) : 0

checkoutPaymentsReceived(booking) =
    sum(attempt.amount for attempt in checkoutPaymentAttempts
        where attempt.bookingId == booking.id
          and attempt.status in (AUTHORIZED, LATE_AUTHORIZED))

balance_due(booking) =
    roomCost(booking)
  + sum(charge.amount for charge in charges where charge.bookingId == booking.id)
  - legacyPaidAmount(booking)
  - checkoutPaymentsReceived(booking)
```

For every booking created under this revision, `legacyRoomPaymentTransactionId`
is always null, so `legacyPaidAmount` is always zero and the formula reduces to
the user's literal `nights × rate + charges - payments received` — with "rate"
meaning the rate *at booking time*, locked into `roomChargeAmount`, not
whatever the room's rate happens to be when balance is later calculated (a
dedicated unit test changes the room's rate after booking and asserts the
balance is unaffected). For a legacy booking (non-null marker),
`legacyPaidAmount` cancels `roomCost` exactly — the same figure is added and
subtracted — leaving `charges - checkoutPaymentsReceived`, i.e. it is never
charged twice for the room it already paid for at booking time (plan.md Key
Design Decision #1; a separate dedicated unit test asserts this directly).

Balance calculation no longer needs the `Room` entity at all — `roomCost` reads
straight off the `Booking` row. Only `createConfirmedBooking` itself (at the
moment of booking, before any rate lock exists yet) needs the room's rate.

## Room board (response projection, unchanged)

| Field | Source | Notes |
|---|---|---|
| *(all layer-2b/2d fields unchanged)* | | |
| `hasBalanceDue` | `balance_due(booking, room) > 0` for the room's `CHECKED_IN` booking | present (`true`/`false`) only when `status == OCCUPIED`; absent/`null` otherwise |
| `guestDetails` *(new)* | the booking's guest-detail fields (name, phone, email, visiting from, adults, children, expected arrival/departure time, notes) | present for both an arriving (pre-check-in) booking and an occupied room's current booking — Clarifications: "existing panel extended... applies to both" |
