# API Contract: Room Board (layer 2b)

All endpoints below are reachable only through `api-gateway`, same as layer 2a.

## `POST /api/v1/bookings/{id}/check-in`

Front-desk staff only (FR-015).

**Response `200`**: the booking's detail (same shape as `GET /api/v1/bookings/{id}`
from layer 2a's contract), now with `status: "CHECKED_IN"`.

**Response `404`**: no booking with that id.

**Response `409`**: the booking is not `CONFIRMED`, or its room is not
`VACANT_CLEAN` — the response body names which precondition failed.

## `POST /api/v1/bookings/{id}/check-out`

Front-desk staff only (FR-015). No request body. No payment-related field, call, or
side effect of any kind (FR-009) — balances/folios are layer 2c.

**Response `200`**: the booking's detail, now with `status: "CHECKED_OUT"`.

**Response `404`**: no booking with that id.

**Response `409`**: the booking is not `CHECKED_IN`.

## `PATCH /api/v1/rooms/{id}/status`

Front-desk **or** housekeeping (FR-016).

**Request**:
```json
{ "status": "VACANT_CLEAN" }
```
`status` must be one of `VACANT_CLEAN`, `DIRTY`, `MAINTENANCE` (never `OCCUPIED` —
that value is only ever reached through check-in).

**Response `200`**:
```json
{ "roomId": "uuid", "status": "VACANT_CLEAN" }
```

**Response `404`**: no room with that id.

**Response `409`**: the requested transition isn't in the allowed set (FR-010) for the
room's current status — the room's status is left unchanged.

## `GET /api/v1/rooms/board`

Front-desk **or** housekeeping (FR-016). No query parameters — always returns every
room.

**Response `200`**:
```json
[
  {
    "floor": 1,
    "rooms": [
      { "roomId": "uuid", "roomNumber": "101", "position": 1, "status": "OCCUPIED",
        "guestName": "Jane Doe", "checkIn": "2026-12-01", "checkOut": "2026-12-03" },
      { "roomId": "uuid", "roomNumber": "102", "position": 2, "status": "VACANT_CLEAN",
        "guestName": null, "checkIn": null, "checkOut": null }
    ]
  },
  { "floor": 2, "rooms": [ /* ... */ ] },
  { "floor": 3, "rooms": [ /* ... */ ] }
]
```
`guestName`/`checkIn`/`checkOut` are present only on a room whose `status` is
`OCCUPIED` (FR-013); `null` otherwise.

## `GET /api/v1/rooms` *(layer 2a, behavior change)*

Unchanged request/response shape. Now additionally excludes any room whose `status`
is `MAINTENANCE` from the results, regardless of that room's booking calendar for the
requested dates (FR-014).
