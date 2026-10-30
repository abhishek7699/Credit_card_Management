# Phase 1 Data Model: Room Board

Extends layer 2a's `booking-service` schema (`booking_service`) — no new tables.

## Room *(extended)*

| Column | Type | Notes |
|--------|------|-------|
| `id` | UUID PK | unchanged — never rewritten by the `V5` migration |
| `room_number` | VARCHAR, UNIQUE, NOT NULL | unchanged |
| `room_type` | VARCHAR, NOT NULL | unchanged |
| `nightly_rate` | DECIMAL(10,2), NOT NULL | unchanged |
| `floor` | INT, NOT NULL *(new)* | 1-3; backfilled from `room_number`'s hundreds digit |
| `position` | INT, NOT NULL *(new)* | display order within the floor; backfilled from `room_number`'s ones digit |
| `status` | VARCHAR(20), NOT NULL, default `VACANT_CLEAN` *(new)* | `VACANT_CLEAN` \| `OCCUPIED` \| `DIRTY` \| `MAINTENANCE` |

- **Constraint** *(new)*: `UNIQUE (floor, position)` — no two rooms can claim the same
  board slot.
- Seeded as 18 rows (3 floors × 6) by `V5`; 14 existing rows backfilled in place, 4 new
  rows (`303`-`306`) inserted to complete floor 3.

### `RoomStatus` (state machine)

```
                     check-in (FR-005)
   VACANT_CLEAN ─────────────────────────► OCCUPIED
        │  ▲                                   │
        │  │ PATCH /status                     │ check-out (FR-007,
        │  │ (MAINTENANCE -> VACANT_CLEAN)      │  unconditional)
        ▼  │                                    ▼
   MAINTENANCE ◄───────────────────────────  DIRTY
        ▲        PATCH /status                 │
        │        (DIRTY/VACANT_CLEAN           │ PATCH /status
        └────────   -> MAINTENANCE)   ◄─────────┘  (DIRTY -> VACANT_CLEAN)
```

- `OCCUPIED` is reached **only** via check-in, and left **only** via check-out (to
  `DIRTY`) — the `PATCH /status` endpoint never accepts `OCCUPIED` as a target, and
  never accepts a transition *out of* `OCCUPIED` either (FR-010/FR-011: the allowed set
  is exactly `DIRTY→VACANT_CLEAN`, `VACANT_CLEAN→MAINTENANCE`, `DIRTY→MAINTENANCE`,
  `MAINTENANCE→VACANT_CLEAN`).
- Every transition above is applied via the conditional-update pattern (research.md
  §2): the `WHERE status = <expected>` clause on each one is exactly the edge in this
  diagram it corresponds to.

## Booking *(extended)*

| Column | Type | Notes |
|--------|------|-------|
| *(all layer-2a columns unchanged)* | | |
| `status` | VARCHAR(20), NOT NULL | now also `CHECKED_IN` \| `CHECKED_OUT`, in addition to 2a's `HELD`/`CONFIRMED`/`CANCELLED` |

No new column — `status`'s existing `VARCHAR(20)` already fits the two new values; no
migration needed for `bookings` itself.

### `BookingStatus` (state machine, extended)

```
  HELD ──► CONFIRMED ──► CHECKED_IN ──► CHECKED_OUT   (both terminal)
   │                                                   additions this layer
   └──► CANCELLED   (terminal, unchanged from 2a)
```

- `CHECKED_IN` is reached only from `CONFIRMED` (FR-005), via the room-then-booking
  conditional-update pair (plan.md Key Design Decision #3).
- `CHECKED_OUT` is reached only from `CHECKED_IN` (FR-007). `CHECKED_OUT` is terminal —
  nothing in this layer ever transitions a booking out of it.
- No interaction with 2a's `CANCELLED`/terminal-state rules: a `CANCELLED` or `HELD`
  booking is simply not `CONFIRMED`, so check-in's precondition already rejects it
  without any new rule being needed.

## Room board (response projection, not a persisted entity)

Assembled at read time (research.md §3) from `Room` (ordered by floor, position) and
the subset of `Booking` rows with `status = CHECKED_IN`, joined by `roomId`:

| Field | Source | Notes |
|---|---|---|
| `floor` | `Room.floor` | grouping key |
| `rooms[].roomId`, `.roomNumber`, `.position`, `.status` | `Room` | every room, always present |
| `rooms[].guestName`, `.checkIn`, `.checkOut` | the room's `CHECKED_IN` `Booking`, if any | present only when `status = OCCUPIED` (FR-013) |
