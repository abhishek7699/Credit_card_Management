# Quickstart: Charges

Manual scenarios aligned to the five user stories (spec.md, renumbered this
revision), run against a live `docker compose up -d --build` stack plus
`npm run dev` in `frontend/` (see README.md's "Frontend" section for the exact
commands). All requests go through `api-gateway`.

## Story 1 — Capture a full guest record and confirm the booking immediately (P1)

1. `POST /api/v1/bookings` with just the required fields (`roomId`, `guestName`,
   `guestPhone`, `checkIn`, `checkOut`) and no `card` field at all → `201`,
   `status: "CONFIRMED"` **immediately**, no polling needed. `balanceDue` already
   equals the full room cost (`nights × room.nightlyRate`).
2. Repeat with every optional field filled in (`guestEmail`, `visitingFrom`,
   `adults`, `children`, `expectedArrivalTime`, `expectedDepartureTime`,
   `notes`) → `201`, all fields echoed back exactly.
3. Omit a required field (e.g. `guestPhone`) → `400`, nothing created.
4. Omit `adults`/`children` → confirm they default to `1`/`0` respectively.
5. Book a second, different room for an overlapping date range → succeeds (the
   room-night constraint is per-room, unaffected by this revision). Book the
   *same* room for an overlapping range → `409`, as before.
6. View the booking's detail both before and after check-in → the full guest
   record and `balanceDue` are visible in both states (Clarifications: the room
   panel shows guest details for arriving *and* occupied bookings).

## Story 2 — Add a charge during a stay (P2)

1. Check a booking in (Story 1's booking, or any `CONFIRMED` one).
2. From its room's panel, add a charge: `ROOM_SERVICE`, "Club sandwich", `18.50`.
3. Confirm it appears as a line item and the balance due is now room cost +
   `18.50`.
4. Add a second charge, a different type (`MINIBAR`, "Two sodas", `6.00`) —
   confirm the balance due increases by exactly `6.00` again.
5. Attempt to add a charge to a booking that is `CONFIRMED` (not yet checked in)
   or already `CHECKED_OUT` → rejected, nothing recorded.

## Story 3 — Balance-due flag on the board (P3)

1. With Story 2's charges still outstanding, view the room board → that room is
   flagged as having a balance due (it already was, the instant it was
   confirmed, since room cost alone is now a balance — the flag was already
   true before any charge was ever added).
2. Check in a *different* room, add no charges, with a non-zero nightly rate →
   view the board → that room is **also** flagged (room cost alone is enough,
   this revision's central behavior change from the prior version of this
   layer).

## Story 4 — Check out with a balance due (P4)

1. Continue from Story 2's room (balance = room cost + `24.50`).
2. Attempt checkout → supply a card the payment core authorizes
   (`4111111111111111`) → the booking stays checked in while the charge is
   decided; the room stays occupied.
3. Wait for the outcome → booking becomes `CHECKED_OUT`, room becomes `DIRTY`.
4. Separately, on a different checked-in booking with a balance, attempt
   checkout with a card the payment core declines → the booking **stays
   `CHECKED_IN`**, the balance due is unchanged, the room stays occupied — retry
   checkout on the same booking and confirm it's offered again.
5. While a checkout attempt is still pending, attempt a second checkout for the
   same booking → rejected.
6. **Stuck-checkout recovery** (unchanged from the prior version of this layer):
   start a checkout attempt, then stop `booking-service` before the outcome
   arrives for longer than the sweep window (10 minutes, `CheckoutAttemptSweepService`)
   → the booking automatically reverts to checked in, balance unchanged. If
   that attempt's outcome arrives afterward as `AUTHORIZED`, confirm the balance
   drops by that attempt's amount — never silently dropped. If it arrives as
   `DECLINED`/`FAILED`, confirm nothing changes.

## Story 5 — Check out with no balance due (P5, now a narrow edge case)

1. Set up a room with a `nightlyRate` of `0.00` (e.g. a comped/test room — this
   is now the *only* way to reach a zero balance, since an ordinary room's cost
   alone creates a balance the instant it's confirmed).
2. Check that room's booking in, add no charges, attempt checkout → completes
   immediately: booking `CHECKED_OUT`, room `DIRTY` — no card requested,
   confirmed no payment-core call was made (no new transaction appears in
   `reporting-service`'s records for this booking).

## Legacy booking — never double-charged for room cost already paid at booking (new)

This scenario cannot be produced through the current API (nothing creates a
legacy-marked booking any more) — it's exercised by a dedicated unit test
seeding a `Booking` row directly with `legacyRoomPaymentTransactionId` set and
`roomChargeAmount` populated, matching what the pre-revision saga used to
produce. Calling `getBalanceDue` on it returns exactly `sum(charges) -
sum(checkoutPayments)` — the room cost is added and immediately cancelled by
the legacy-payment term, never counted twice (data-model.md's balance formula;
plan.md Key Design Decision #1).

## End-to-end (matches e2e step 15)

Book with no card → `CONFIRMED` immediately → check in → add a room-service
charge → check out with a card covering room cost + charge → `CHECKED_OUT`,
room `DIRTY`.
