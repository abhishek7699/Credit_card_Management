# Phase 1 Data Model: Charges

Extends `booking-service`'s existing schema (`booking_service`) — two new tables,
one new column on `bookings`. No existing column changed or dropped.

## `Booking` *(extended)*

| Column | Type | Notes |
|--------|------|-------|
| *(all existing columns unchanged)* | | |
| `room_charge_amount` | DECIMAL(10,2), nullable *(new)* | Set once, inside `initiatePayment`, to the exact amount charged at booking time (research.md §2). Nullable because it isn't known until that call runs — same timing as the existing `transaction_id` column it sits beside. |
| `status` | VARCHAR(20), NOT NULL | now also `CHECKOUT_PENDING`, in addition to the existing `HELD`/`CONFIRMED`/`CANCELLED`/`CHECKED_IN`/`CHECKED_OUT` |

No migration needed for `status`'s own column — its existing `VARCHAR(20)` already
fits the new value.

### `BookingStatus` (state machine, extended)

```
  ... CONFIRMED ──► CHECKED_IN ──┬─► CHECKOUT_PENDING ──► CHECKED_OUT   (terminal)
                                 │         │
                                 │         └──(declined/failed/expired)──┐
                                 │                                        │
                                 └────────────◄───────────────────────────┘
                     (zero balance: CHECKED_IN ──► CHECKED_OUT directly, no pending step)
```

- `CHECKOUT_PENDING` is reached only from `CHECKED_IN`, and only when the balance
  due at that moment is greater than zero (FR-007). It is left either to
  `CHECKED_OUT` (the attempt is approved, FR-010) or back to `CHECKED_IN` (declined,
  failed, or swept as expired after the hold-expiry window — FR-011, FR-012a).
- A zero-balance checkout (FR-013) never passes through `CHECKOUT_PENDING` at all —
  `CHECKED_IN` → `CHECKED_OUT` directly, exactly as layer 2b's checkout already
  worked before this layer existed.
- At most one `CheckoutPaymentAttempt` is ever `PENDING` for a given booking at a
  time — enforced by rejecting a second checkout request while the booking is
  already `CHECKOUT_PENDING` (FR-009).

## `Charge` *(new table: `charges`)*

| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `booking_id` | UUID, NOT NULL, FK-by-convention to `bookings.id` (no DB-level FK, matching this schema's existing convention — e.g. `booking_room_nights.booking_id`) | |
| `type` | VARCHAR(20), NOT NULL | `ROOM_SERVICE` \| `MINIBAR` \| `LAUNDRY` \| `OTHER` |
| `description` | VARCHAR(500), NOT NULL | free text |
| `amount` | DECIMAL(10,2), NOT NULL | |
| `created_at` | TIMESTAMPTZ, NOT NULL | ordering for FR-003's "in the order they were added" |

Add-only (Assumptions) — no update or delete path exists anywhere in this layer.

## `CheckoutPaymentAttempt` *(new table: `checkout_payment_attempts`)*

| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `booking_id` | UUID, NOT NULL | same by-convention reference as `charges.booking_id` |
| `transaction_id` | VARCHAR(100), nullable | set once the gRPC call returns a transaction id; stays null if the call itself fails outright (mirrors `initiatePayment`'s existing `StatusRuntimeException` handling — research.md §4/plan.md Key Design Decision #4: a null-transactionId attempt is swept the same way a never-answered one is, by the same sweep, after the same window) |
| `amount` | DECIMAL(10,2), NOT NULL | the balance due at the moment this attempt was created — fixed at creation, not recomputed later |
| `status` | VARCHAR(20), NOT NULL | see below |
| `created_at` | TIMESTAMPTZ, NOT NULL | the sweep's age check |
| `updated_at` | TIMESTAMPTZ, NOT NULL | |

### `CheckoutAttemptStatus` (state machine)

```
  PENDING ──► AUTHORIZED           (approved — booking checks out, FR-010)
     │
     ├──► DECLINED | FAILED        (booking returns to CHECKED_IN, FR-011)
     │
     └──► EXPIRED ──► LATE_AUTHORIZED   (late approval recorded as a payment, FR-012b)
              │
              └─► (stays EXPIRED)       (late decline/failure — true no-op)
```

- `PENDING → EXPIRED`: the sweep (plan.md Key Design Decision #4), once this
  attempt is older than the existing hold-expiry window with no outcome yet.
- `EXPIRED → LATE_AUTHORIZED`: a late `AUTHORIZED` Kafka outcome for an attempt
  already swept — mirrors `CancellationReason`'s own `HOLD_EXPIRED` →
  `PAYMENT_AFTER_EXPIRY` precedent exactly (research.md §4). Its `amount` counts
  toward the booking's balance calculation the same as a normal `AUTHORIZED`
  attempt would — the booking's own current status is never reversed by this, only
  the balance figure changes, and a WARN is logged for manual follow-up if the
  booking has already moved on by the time this arrives (e.g., already checked out
  a different way).
- `EXPIRED` stays `EXPIRED` on a late `DECLINED`/`FAILED` outcome — nothing to
  record, true no-op.
- Balance due (FR-004) subtracts the `amount` of every attempt whose status is
  `AUTHORIZED` or `LATE_AUTHORIZED` — both count identically once resolved; only
  the audit trail distinguishes "approved in the normal flow" from "approved late,
  after this attempt had already been swept."

## Balance due (computed, not persisted)

```
balance_due(booking) =
    booking.roomChargeAmount
  + sum(charge.amount for charge in charges where charge.bookingId == booking.id)
  - booking.roomChargeAmount
  - sum(attempt.amount for attempt in checkoutPaymentAttempts
        where attempt.bookingId == booking.id
          and attempt.status in (AUTHORIZED, LATE_AUTHORIZED))
```

The first and third terms cancel today (Assumptions: the full room cost is already
charged at booking time) — kept explicit anyway per FR-004, so the formula stays
correct even if a future layer changes how or when the original room charge works.

## Room board (response projection, extended)

| Field | Source | Notes |
|---|---|---|
| *(all layer-2b/2d fields unchanged)* | | |
| `hasBalanceDue` | `balance_due(booking) > 0` for the room's `CHECKED_IN` booking | **new** — present (`true`/`false`) only when `status == OCCUPIED`; absent/`null` otherwise, same presence rule as `guestName`/`currentBookingId` |
