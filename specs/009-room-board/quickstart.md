# Quickstart: Room Board

Test scenarios aligned to the four user stories. All requests go through
`api-gateway` with a role-appropriate JWT.

## Story 1 — View the board (P1)

1. `GET /api/v1/rooms/board` (FRONT_DESK or HOUSEKEEPING) → 3 floors, 6 rooms each, 18
   total, every room `VACANT_CLEAN`, no `guestName`/dates on any of them.
2. Call it with a JWT lacking both roles → rejected (403) before reaching
   `booking-service`.

## Story 2 — Front desk checks a guest in (P2)

1. Run layer 2a's booking flow to a `CONFIRMED` booking (quickstart.md Story 1 there).
2. `POST /api/v1/bookings/{id}/check-in` (FRONT_DESK) → `200`, booking `CHECKED_IN`.
3. `GET /api/v1/rooms/board` → that room is `OCCUPIED` with the guest's name and dates.
4. Repeat step 2 on the same booking → `409` (no longer `CONFIRMED`).
5. `POST /api/v1/bookings/{id}/check-in` on a different `CONFIRMED` booking for the
   *same* room/overlapping dates — should not be reachable in practice since 2a's
   double-booking guard already prevents two bookings for the same room/night, but if
   attempted directly against an already-`OCCUPIED` room, rejected (409).

## Story 3 — Front desk checks a guest out (P3)

1. Continue from Story 2's checked-in booking.
2. `POST /api/v1/bookings/{id}/check-out` (FRONT_DESK) → `200`, booking `CHECKED_OUT`,
   no payment-related field anywhere in the response.
3. `GET /api/v1/rooms/board` → that room is `DIRTY`, no guest details.
4. Repeat step 2 → `409` (no longer `CHECKED_IN`).

## Story 4 — Housekeeping updates room status (P4)

1. Continue from Story 3's `DIRTY` room.
2. `PATCH /api/v1/rooms/{id}/status` with `{"status":"VACANT_CLEAN"}` (HOUSEKEEPING)
   → `200`.
3. `GET /api/v1/rooms/board` → that room is `VACANT_CLEAN` again.
4. `PATCH .../status` with `{"status":"MAINTENANCE"}` on a `VACANT_CLEAN` or `DIRTY`
   room → `200`.
5. `GET /api/v1/rooms?checkIn=...&checkOut=...` for a range that room would otherwise
   be free for → that room does not appear, even though nothing is booked against it.
6. `PATCH .../status` with `{"status":"VACANT_CLEAN"}` on that `MAINTENANCE` room →
   `200`, and it reappears in availability search.
7. `PATCH .../status` with any target not in the allowed set for the room's current
   status (e.g. `{"status":"DIRTY"}` on a `VACANT_CLEAN` room, or any target at all on
   an `OCCUPIED` room) → `409`, status unchanged.

## Edge case — concurrent check-in attempts for the same booking

1. Create a `CONFIRMED` booking.
2. Fire two concurrent `POST .../check-in` requests for it.
3. Assert exactly one returns `200`; the other returns `409`. The booking ends in
   `CHECKED_IN` exactly once (not corrupted, not double-applied).

## End-to-end (matches e2e step 14)

Book → confirm → check-in → board shows `OCCUPIED` → check-out → board shows `DIRTY`
→ housekeeping marks it `VACANT_CLEAN` → board shows `VACANT_CLEAN` again.
