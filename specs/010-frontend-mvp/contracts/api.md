# API Contract: Frontend MVP (Staff Room Board)

All endpoints below (except the dev-login/JWKS ones, which bypass the gateway by
design) are reachable only through `api-gateway`, same as layers 2a/2b.

## `POST /dev/login` *(new — `auth-mock`, not `api-gateway`)*

Dev-only. Issues a bearer token for the chosen role; no password, PIN, or real
identity check of any kind (research.md §0, SECURITY.md).

**Request**:
```json
{ "role": "FRONT_DESK" }
```
`role` must be `FRONT_DESK` or `HOUSEKEEPING`.

**Response `200`**:
```json
{ "token": "<RS256 JWT, authorities: [\"ROLE_FRONT_DESK\"], exp: now+4h>" }
```

**Response `400`**: `role` missing or not one of the two allowed values.

Reached directly at `http://localhost:9000/dev/login` in local dev (not proxied
through the gateway — it has no business routes to the gateway's own `RouteConfig`,
only JWKS, which `api-gateway` itself consults server-side).

## `GET /api/v1/rooms/board` *(layer 2b, response extended — additive only)*

Front-desk **or** housekeeping. No query parameters — always returns every room.
Unchanged fields keep their exact existing meaning; three new fields are appended per
room entry, each `null` unless noted.

**Response `200`**:
```json
[
  {
    "floor": 1,
    "rooms": [
      { "roomId": "uuid", "roomNumber": "101", "position": 1, "status": "OCCUPIED",
        "guestName": "Jane Doe", "checkIn": "2026-12-01", "checkOut": "2026-12-03",
        "currentBookingId": "uuid-of-jane-does-booking",
        "arrivingBookingId": null, "arrivingGuestName": null },
      { "roomId": "uuid", "roomNumber": "102", "position": 2, "status": "VACANT_CLEAN",
        "guestName": null, "checkIn": null, "checkOut": null,
        "currentBookingId": null,
        "arrivingBookingId": "uuid-of-johns-confirmed-booking",
        "arrivingGuestName": "John Smith" }
    ]
  },
  { "floor": 2, "rooms": [ /* ... */ ] },
  { "floor": 3, "rooms": [ /* ... */ ] }
]
```

- `currentBookingId`: present only when `status == "OCCUPIED"` — the id of the
  booking check-out must be called on for this room.
- `arrivingBookingId` / `arrivingGuestName`: present only when a `CONFIRMED` booking
  exists for this room with `checkIn` equal to today — the id check-in must be called
  on, and that arriving guest's name. **Independent** of `guestName`/`currentBookingId`
  — both pairs can be populated on the same room at once (a same-day turnover: one
  guest still occupying, a different guest's booking arriving later today).

## `POST /api/v1/bookings` *(layer 2a, unchanged — consumed as-is)*

Front-desk only. `BookingForm` submits `roomId`, `checkIn`, `checkOut`, `guestName`,
and `card` (forwarded straight through, per FR-013) exactly as layer 2a's existing
contract defines. Returns `201` with `{ bookingId, status: "HELD" }`.

## `GET /api/v1/bookings/{id}` *(layer 2a, unchanged — consumed as-is)*

Front-desk only. `BookingStatusView` polls this (research.md §5: every 10s while
`status == "HELD"`, stops once `CONFIRMED` or `CANCELLED`) to show the booking's
progress.

## `POST /api/v1/bookings/{id}/check-in` *(layer 2b, unchanged — consumed as-is)*

Front-desk only. Called with the board entry's `arrivingBookingId`.

## `POST /api/v1/bookings/{id}/check-out` *(layer 2b, unchanged — consumed as-is)*

Front-desk only. Called with the board entry's `currentBookingId`.

## `PATCH /api/v1/rooms/{id}/status` *(layer 2b, unchanged — consumed as-is)*

Front-desk **or** housekeeping. Called with `{ "status": "VACANT_CLEAN" }` or
`{ "status": "MAINTENANCE" }` per `RoomActionPanel`'s offered actions (FR-018/FR-019).

## Error handling (all of the above, via `api-gateway`)

| Status | Frontend behavior |
|---|---|
| `401` | Session cleared; `StaffAuthGate` renders `LoginScreen` (research.md §6). |
| `403` | Inline "not allowed" message where the action was attempted; session untouched. |
| `409` | Inline message naming the rejected transition (e.g., "room is no longer vacant"); the board's next poll corrects the displayed state. |
| `503` / network error | `RetryBanner` in place of the affected view; polling continues to retry on its existing interval. |
