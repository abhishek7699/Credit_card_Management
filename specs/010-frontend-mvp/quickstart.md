# Quickstart: Frontend MVP (Staff Room Board)

Manual scenarios aligned to the five user stories, run against a live
`docker compose up -d --build` stack plus `npm run dev` in `frontend/` (exact commands
in the P1-P2 checkpoint message / README). All requests the UI makes go through
`api-gateway`; sign-in goes directly to the new `auth-mock`.

## Story 1 — Sign in and view the live board (P1)

1. Open the app at `/staff`. Land on the login screen, not the board.
2. Pick **Front Desk**, sign in → land on the board: 3 floors × 6 rooms, every room
   `VACANT_CLEAN` on a fresh stack, no guest names.
3. In a second tab/terminal, change a room's status directly against the API (e.g.
   `PATCH /api/v1/rooms/{id}/status` via curl with a `HOUSEKEEPING`-role token from
   `/dev/login`) → within 10 seconds, the open board tab reflects it with no reload.
4. Stop `booking-service` (or the whole stack) → the board shows a retry banner
   instead of freezing or silently going blank. Restart it → the board recovers on
   its own within one more poll.
5. Wait out the session (or clear `sessionStorage` to simulate it) → the next action
   returns you to the login screen.

## Story 2 — Book a room for a guest (P2)

1. As Front Desk, click a `VACANT_CLEAN` room → a booking form (guest name, stay
   dates, card fields).
2. Submit with a card the payment core authorizes (`4111111111111111`, matching the
   rest of this repo's test fixtures) → the UI shows the booking as `HELD`, then
   `CONFIRMED` within a few seconds, with no manual refresh.
3. Submit a different room's booking with a declined-card fixture → the UI shows
   `CANCELLED`, and that room is still bookable (not stuck).
4. Open the browser's dev tools (Application → Storage, and the Network tab's request
   payloads) at any point during/after either submission → the card number/CVV never
   appear in `sessionStorage`/`localStorage`, and never appear in the app's own console
   output.
5. Sign in as **Housekeeping** instead → the same `VACANT_CLEAN` room offers no
   booking action.

## Story 3 — Check a guest in (P3)

1. Continue from a booking that just reached `CONFIRMED` in Story 2.
2. As Front Desk, the board shows that room with a check-in action available
   (`arrivingBookingId` populated) → click it → room becomes `OCCUPIED` with the
   guest's name, on the next refresh.
3. From a **different** browser session/tab (a fresh sign-in, not the one that booked
   it) → the same room's check-in action is still offered and still works — proving
   it's board-driven, not session-memory-driven.
4. As Housekeeping, view the same room before check-in → no check-in action offered.

## Story 4 — Check a guest out (P4)

1. From the occupied room above, as Front Desk, click it → check-out action → room
   becomes `DIRTY` on the next refresh, no payment/charge screen anywhere in the flow.
2. As Housekeeping, view an occupied room → no check-out action offered.

## Story 5 — Update room status (P5)

1. From the `DIRTY` room above, as either role, mark it `VACANT_CLEAN` → board updates,
   room becomes bookable again (reappears as a click-to-book target).
2. Mark a `VACANT_CLEAN` or `DIRTY` room `MAINTENANCE` → board shows it under
   maintenance; it no longer offers a booking action.
3. Mark that room `VACANT_CLEAN` again → bookable again.
4. Attempt (via the API directly, since the UI won't offer it) a disallowed
   transition, e.g. `OCCUPIED → DIRTY` → `409`, confirmed the board's displayed status
   is unaffected.

## End-to-end (matches the Playwright smoke test)

Sign in as Front Desk → book a vacant room → booking reaches `CONFIRMED` → check it
in → board shows that room `OCCUPIED` with the guest's name.
