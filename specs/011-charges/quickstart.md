# Quickstart: Charges

Manual scenarios aligned to the four user stories, run against a live
`docker compose up -d --build` stack plus `npm run dev` in `frontend/` (see
README.md's "Frontend" section for the exact commands). All requests go through
`api-gateway`.

## Story 1 — Add a charge during a stay (P1)

1. Check a booking in (layer 2b flow).
2. From its room's panel, add a charge: `ROOM_SERVICE`, "Club sandwich", `18.50`.
3. Confirm it appears as a line item and the balance due shows `18.50`.
4. Add a second charge, a different type (`MINIBAR`, "Two sodas", `6.00`) — confirm
   the balance due becomes `24.50`.
5. Attempt to add a charge to a booking that is `CONFIRMED` (not yet checked in) or
   already `CHECKED_OUT` → rejected, nothing recorded.

## Story 2 — Balance-due flag on the board (P2)

1. With the charges from Story 1 still outstanding, view the room board → that
   room is flagged as having a balance due.
2. Check in a different room with no charges → view the board → that room is
   *not* flagged.

## Story 3 — Check out with a balance due (P3)

1. Continue from Story 1's room (balance `24.50`).
2. Attempt checkout → supply a card the payment core authorizes
   (`4111111111111111`) → the booking stays checked in while the charge is
   decided; the room stays occupied.
3. Wait for the outcome → booking becomes `CHECKED_OUT`, room becomes `DIRTY`.
4. Separately, on a different checked-in booking with a balance, attempt checkout
   with a card the payment core declines → the booking stays checked in, the
   balance due is unchanged, the room stays occupied — retry checkout on the same
   booking and confirm it's offered again.
5. While a checkout attempt is still pending, attempt a second checkout for the
   same booking → rejected.
6. **Stuck-checkout recovery** (Clarifications): start a checkout attempt, then
   stop `booking-service` before the outcome arrives (or otherwise prevent the
   Kafka event from being delivered) for longer than the hold-expiry window (10
   minutes) → the booking automatically reverts to checked in, balance unchanged.
   If that attempt's outcome arrives afterward as `AUTHORIZED`, confirm the
   balance drops by that attempt's amount (to zero, if it was the only charge) —
   never silently dropped. If it arrives as `DECLINED`/`FAILED`, confirm nothing
   changes.

## Story 4 — Check out with no balance due (P4)

1. Check in a room, add no charges, attempt checkout → completes immediately:
   booking `CHECKED_OUT`, room `DIRTY` — no card requested, confirmed no
   payment-core call was made (no new transaction appears in
   `reporting-service`'s records for this booking).

## End-to-end (matches e2e step 15)

Check in → add a room-service charge → check out with that balance (card
authorized) → `CHECKED_OUT`, room `DIRTY`.
