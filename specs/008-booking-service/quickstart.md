# Quickstart: Booking Service

Test scenarios aligned to the revised (staff-actor) user stories. All requests go
through `api-gateway` with a FRONT_DESK-role JWT unless noted.

## Story 1 — Staff books a room and reaches CONFIRMED (P1)

1. Seed a room via Flyway (e.g. room `101`, `STANDARD`, `$129.00/night`).
2. `GET /api/v1/rooms?checkIn=2026-11-01&checkOut=2026-11-03` → room `101` appears.
3. `POST /api/v1/bookings` with room `101`, those dates, a guest name, and a card that
   the mock/test payment path authorizes → `201`, `status: HELD`.
4. Wait for the `transaction.status.updated` `AUTHORIZED` event to be consumed → booking
   status becomes `CONFIRMED` (`GET /api/v1/bookings/{id}`).
5. Repeat step 2 for the same room/dates → room `101` no longer appears (held/confirmed
   bookings are excluded).
6. Call step 2 or 3 with a JWT lacking `FRONT_DESK`/`ADMIN` → rejected (403) before
   reaching booking-service.

This is e2e **step 12**: book a room, reach CONFIRMED.

## Story 2 — Declined payment releases the room (P2)

1. `POST /api/v1/bookings` with a card the test payment path declines → `201`,
   `status: HELD`.
2. Wait for the `DECLINED` outcome event → booking status becomes `CANCELLED`,
   `cancellationReason: PAYMENT_DECLINED`.
3. `GET /api/v1/rooms` for the same room/dates → room reappears.

This is e2e **step 13**: declined card → CANCELLED and room available again.

## Story 3 — Abandoned bookings expire automatically (P3)

1. `POST /api/v1/bookings` for a room, do not send any payment outcome.
2. Age the booking's `created_at` past `booking.hold-timeout-minutes` (test-only: insert
   directly, or configure a short timeout in the test profile).
3. Let `HoldExpiryService` run (or invoke it directly in an integration test) →
   booking status becomes `CANCELLED`, `cancellationReason: HOLD_EXPIRED`.
4. `GET /api/v1/rooms` for the same room/dates → room reappears.

## Edge case — AUTHORIZED outcome arrives after expiry (FR-019, no reverse transition)

1. Create a booking, let it expire (as in Story 3) → `CANCELLED`,
   `cancellationReason: HOLD_EXPIRED`.
2. Deliver a late `AUTHORIZED` outcome for that same `transactionId`.
3. Assert the booking **stays** `CANCELLED`, and its `cancellationReason` is updated to
   `PAYMENT_AFTER_EXPIRY` — it must never become `CONFIRMED`. Assert a WARN-level log
   line was emitted for manual refund follow-up.

## Edge case — duplicate outcome delivery (FR-012)

1. Create a booking, let it reach `CONFIRMED` via one `AUTHORIZED` event.
2. Redeliver the exact same event (same `eventId`).
3. Assert no change in state and no duplicate side effect — the `processed_events` row
   for that `eventId` makes the second delivery a no-op.

## Edge case — outcome event arrives before `transactionId` is committed (research.md §3a)

1. Deliver a `transaction.status.updated` event whose `payload.transactionId` does not
   (yet) match any booking.
2. Assert the consumer throws rather than swallowing it, and that **no**
   `processed_events` row is written for that `eventId`.
3. Simulate the booking's `transaction_id` committing, then redeliver the same event.
4. Assert it is now applied normally (e.g. → CONFIRMED) and a `processed_events` row is
   written this time.

## Edge case — payment gRPC call exceeds its deadline

1. Point the test double for `transaction-service` at a handler that never responds
   within 5 seconds.
2. `POST /api/v1/bookings` → still `201`, `status: HELD` (the booking was persisted HELD
   before the call was attempted).
3. Assert no `transaction_id` is set and a WARN was logged for the deadline exceeded.
4. Age the booking past the hold window and run expiry (Story 3) → `CANCELLED`,
   `cancellationReason: HOLD_EXPIRED` — confirming hold expiry, not the call site, is
   what resolves it.
