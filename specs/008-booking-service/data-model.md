# Phase 1 Data Model: Booking Service

Schema owned exclusively by `booking-service` (Principle V) — no other service queries
these tables directly.

## Room

| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `room_number` | VARCHAR, UNIQUE, NOT NULL | e.g. "101" |
| `room_type` | VARCHAR, NOT NULL | e.g. "STANDARD", "SUITE" — free-text in this MVP, no type table |
| `nightly_rate` | DECIMAL(10,2), NOT NULL | used as-is; no dynamic pricing (spec Assumptions) |

Seeded via Flyway (`V1__create_rooms.sql` + a seed migration), consistent with the
original Input's "Rooms seeded via Flyway."

## Booking

| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `room_id` | UUID, FK → `room.id`, NOT NULL | |
| `guest_name` | VARCHAR, NOT NULL | guest identified by name only (spec Assumptions) |
| `check_in` | DATE, NOT NULL | |
| `check_out` | DATE, NOT NULL | MUST be strictly after `check_in` (FR-003) |
| `status` | VARCHAR, NOT NULL | `HELD` \| `CONFIRMED` \| `CANCELLED` |
| `cancellation_reason` | VARCHAR, NULLABLE | set only when `status = CANCELLED`; see enum below |
| `transaction_id` | VARCHAR, NULLABLE | set once payment authorization is initiated (FR-009) |
| `created_at` | TIMESTAMPTZ, NOT NULL, default `now()` | drives hold-expiry age check |
| `updated_at` | TIMESTAMPTZ, NOT NULL | |

**Never stored on this entity, by design (Principle I / FR-008)**: card number, expiry,
CVV, cardholder name as submitted on the card — the request DTO carries them only as far
as the gRPC call to `transaction-service` and they are discarded immediately after.

### `BookingStatus` (state machine)

```
            payment AUTHORIZED
   HELD ───────────────────────────► CONFIRMED  (terminal)
     │
     │ payment DECLINED/FAILED, or hold-expiry timeout
     ▼
 CANCELLED  (terminal)
```

- **HELD** is the only non-terminal state and the only one a new booking can start in
  (FR-006).
- **CONFIRMED** and **CANCELLED** are terminal (FR-023): once reached, no further
  transition is ever applied to that booking, **including** a late-arriving AUTHORIZED
  outcome for an already-CANCELLED (expired) booking — that outcome only sets
  `cancellation_reason = PAYMENT_AFTER_EXPIRY` and logs a warning; it never moves the
  booking back to CONFIRMED (FR-019). There is no guest-cancellation transition in this
  version — self-service cancellation was removed.

### `CancellationReason` (enum)

| Value | Set when |
|-------|----------|
| `PAYMENT_DECLINED` | FR-011 — payment outcome was DECLINED |
| `PAYMENT_FAILED` | FR-011 — payment outcome was FAILED |
| `HOLD_EXPIRED` | FR-015 — hold-expiry job cancelled an abandoned HELD booking |
| `PAYMENT_AFTER_EXPIRY` | FR-019 — an AUTHORIZED outcome arrived after the booking was already cancelled by hold expiry |

(`PAYMENT_AFTER_CANCEL` from the prior clarification round no longer exists — it was
removed along with guest self-cancellation, since that case can no longer occur.)

## BookingRoomNight

Supports the double-booking guarantee (research.md §1) and doubles as the availability
query's join target.

| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | |
| `booking_id` | UUID, FK → `booking.id`, NOT NULL | |
| `room_id` | UUID, FK → `room.id`, NOT NULL | denormalized for the unique constraint below |
| `stay_date` | DATE, NOT NULL | one row per night of the stay, `[check_in, check_out)` |

- **Constraint**: `UNIQUE (room_id, stay_date)` — the sole enforcement mechanism for
  "at most one HELD or CONFIRMED booking per room per night" (FR-007). Rows for a
  booking are inserted when it becomes HELD and deleted when it becomes CANCELLED,
  **in the same CockroachDB transaction as the status/cancellation_reason update** —
  never a separate or deferred step — so the room is immediately re-searchable
  (SC-003/SC-004). CONFIRMED bookings keep their rows (the room stays taken). The one
  exception: the late-`AUTHORIZED`-after-expiry handler (FR-019) never touches this
  table — by the time it runs, the booking's rows were already deleted when it was
  originally cancelled by expiry.
- Availability search (`GET /api/v1/rooms?checkIn&checkOut`, FR-002) is: rooms with no
  `booking_room_night` row for any date in `[checkIn, checkOut)`.

## ProcessedEvent

Standard idempotency table, identical in shape to every other service's (Principle III).

| Column | Type | Notes |
|--------|------|-------|
| `idempotency_key` | VARCHAR PK | `booking:transaction.status.updated:{eventId}` |
| `processed_at` | TIMESTAMPTZ, NOT NULL | |

The Kafka consumer inserts this row and applies the booking's state transition in the
same CockroachDB transaction (Principle III) — **only when a booking matching the
event's `transactionId` is found**. If no booking matches yet (the race described in
research.md §3a: the event can arrive before the booking's own `transaction_id` column
commits), no row is inserted here and the consumer throws so the event is redelivered;
the event is only ever considered "processed" once it is actually applied to a booking.
