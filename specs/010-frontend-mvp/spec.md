# Feature Specification: Frontend MVP (Staff Room Board)

**Feature Branch**: `010-frontend-mvp`

**Created**: 2026-10-30

**Status**: Draft

**Input**: User description: "Build layer 2d: Frontend MVP, connecting the existing React prototype (frontend/) to the real backend. Minimal, B2B staff only. Follow CLAUDE.md working approach. Connection: Replace the RTK Query mock base query with a real fetchBaseQuery against api-gateway. Use a Vite dev proxy (/api -> http://localhost:8088) so no CORS changes are needed. API base URL from env (VITE_API_BASE_URL) for later deployment. Auth (dev only for now): login screen with role picker (FRONT_DESK / HOUSEKEEPING) fetches a JWT from the existing auth-mock, sends it as Bearer on every request. Handle 401 -> back to login, 403 -> 'not allowed' message, 503 -> retry banner. PIN auth backend stays in BACKLOG.md. Screens (real data only): Room board: floor-plan grid by floor (3 x 6), colored by status, guest name if occupied. Polls GET /api/v1/rooms/board every 10s. Click a room: available -> create booking form (guest name, dates, card); confirmed -> check-in; occupied -> check-out; dirty/maintenance -> status change (per role). Booking status view: shows HELD -> CONFIRMED / CANCELLED (poll until final). Rules: Card details go straight to the API; never stored in Redux, localStorage, or logs. TypeScript strict, Vitest + React Testing Library for components (MSW for API mocks). One Playwright smoke test against docker-compose: login -> book -> CONFIRMED -> check-in -> board shows OCCUPIED. Wire into CI (build + tests). README: how to run frontend with the backend. Out of scope: owner dashboard, charges (2c), real auth/PIN, mobile, styling polish."

## Clarifications

### Session 2026-10-30

- Q: Check-out (FR-015) needs a booking reference for any occupied room, not just ones a given staff session created — and check-in (Story 3) similarly needs a way to recognize a room as awaiting arrival without relying on session memory. Is a small, additive extension to the existing `GET /api/v1/rooms/board` response (booking-service only, no payment changes) in scope for this layer to close both gaps? → A: Approved. Each room entry gains `currentBookingId` when `OCCUPIED` (for check-out), and `arrivingBookingId` plus the guest's name when a `CONFIRMED` booking's check-in date is today (for check-in). booking-service's tests are updated to keep 100% coverage, and its e2e coverage is updated if affected.

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Staff Log In and View the Live Room Board (Priority: P1)

A front-desk or housekeeping staff member opens the app, picks their role, signs in, and sees the real room board — every room, grouped by floor, colored by its actual current status, with the guest's name on any occupied room — refreshing on its own without a manual reload.

**Why this priority**: This is the entire point of the layer — retiring the mock data and putting a real, live view of the property in front of staff. Nothing else in this layer matters until this exists.

**Independent Test**: Sign in as each role, confirm the board renders every room from the real backend grouped and colored correctly, and confirm a status change made outside the app (e.g., directly against the API) appears in the UI within one polling cycle with no manual action.

**Acceptance Scenarios**:

1. **Given** the login screen, **When** staff pick a role and sign in, **Then** they land on a room board showing every room from the real backend, grouped by floor and colored by status.
2. **Given** a signed-in session, **When** a room's status changes on the backend (by any means), **Then** the board reflects the new status within the next automatic refresh, with no page reload.
3. **Given** a signed-in session, **When** the backend becomes unreachable, **Then** the board stops silently failing and instead shows a visible retry option, resuming normal updates once the backend responds again.
4. **Given** a signed-in session whose credentials are no longer valid, **When** any request is made, **Then** staff are returned to the login screen.

---

### User Story 2 — Staff Book a Room for a Guest (Priority: P2)

A front-desk staff member clicks a vacant, clean room on the board, fills in the guest's name, stay dates, and card details, submits it, and watches the booking resolve from held to either confirmed or cancelled — entirely inside the app.

**Why this priority**: The board alone (Story 1) is read-only; this is the first real staff action and the one every later action in the stay lifecycle depends on existing.

**Independent Test**: As a front-desk-signed-in session, click a vacant/clean room, submit a booking, and confirm the UI shows the booking moving from held to its final state (confirmed or cancelled) without requiring a page reload, and that no card detail is ever retained anywhere in the app after submission.

**Acceptance Scenarios**:

1. **Given** a vacant, clean room, **When** front-desk staff submit a booking for it with valid card details, **Then** the UI shows the booking as held, then automatically updates to confirmed once the backend settles it.
2. **Given** a submitted booking whose payment is declined, **When** the backend settles it, **Then** the UI shows the booking as cancelled, with the room remaining available, and no stuck "held" state.
3. **Given** a booking form, **When** staff submit it, **Then** the card details are sent directly to the backend and never appear afterward in the app's stored state, browser storage, or console/network logs the app itself writes.
4. **Given** a housekeeping-signed-in session, **When** they view a vacant room, **Then** no booking action is offered to them.

---

### User Story 3 — Staff Check a Guest In (Priority: P3)

Once a booking reaches confirmed for a guest arriving today, front-desk staff check the guest in directly from the board — whether or not this session is the one that created or confirmed that booking — and the board immediately shows that room as occupied with the guest's name.

**Why this priority**: Completes the arrival half of the stay lifecycle that Story 2 set up; the board (Story 1) and booking (Story 2) are each already useful without it.

**Independent Test**: Carry a booking from Story 2 through to confirmed, check it in from the board alone (no separate lookup step), and confirm the room's board entry becomes occupied with the guest's name — repeat starting from a fresh session that did not create the booking, to confirm it's discoverable from the board itself, not session memory.

**Acceptance Scenarios**:

1. **Given** a room with a confirmed booking arriving today, **When** front-desk staff check it in from the board, **Then** the board shows that room as occupied with the guest's name on the very next refresh — regardless of which session created or confirmed that booking.
2. **Given** a check-in attempt the backend rejects (e.g., a race with another session), **When** it fails, **Then** staff see a clear message and the room's displayed status is corrected to match the backend, not left stale.
3. **Given** a housekeeping-signed-in session, **When** they view a room with a confirmed booking pending arrival, **Then** no check-in action is offered to them.

---

### User Story 4 — Staff Check a Guest Out (Priority: P4)

A front-desk staff member clicks an occupied room on the board and checks the guest out; the room immediately shows as dirty, with no payment or charge of any kind happening as part of this action.

**Why this priority**: Completes the stay lifecycle, but the board and the arrival half (Stories 1-3) already demonstrate real value without it.

**Independent Test**: From an occupied room shown on the board — including one checked in by a different session, not just one this session created — check it out, and confirm the room immediately shows as dirty with no payment step in the UI.

**Acceptance Scenarios**:

1. **Given** any room the board currently shows as occupied, **When** front-desk staff check it out, **Then** the room's board entry becomes dirty, with no payment form, charge, or balance shown anywhere in the flow.
2. **Given** a housekeeping-signed-in session, **When** they view an occupied room, **Then** no check-out action is offered to them.

---

### User Story 5 — Staff Update Room Status (Priority: P5)

A housekeeping or front-desk staff member clicks a dirty or in-maintenance room and marks it clean, or takes a room in or out of service, so the board — and what guests can be booked into — stays accurate.

**Why this priority**: Closes the loop back to a bookable room, but Stories 1-4 already demonstrate the full stay lifecycle once through without it; this is what makes the lifecycle repeatable.

**Independent Test**: From a dirty room, mark it clean and confirm the board updates and the room becomes bookable again; from a clean or dirty room, mark it under maintenance and confirm it disappears from what staff can book.

**Acceptance Scenarios**:

1. **Given** a dirty room, **When** staff (front-desk or housekeeping) mark it clean, **Then** the board shows it as vacant and clean, and it becomes bookable again.
2. **Given** a clean or dirty room, **When** staff mark it under maintenance, **Then** the board shows it under maintenance and it no longer appears as bookable.
3. **Given** a room in maintenance, **When** staff mark it clean again, **Then** the board shows it as vacant and clean, and it becomes bookable again.
4. **Given** either staff role, **When** they view a dirty or in-maintenance room, **Then** the status-change action is offered (unlike booking/check-in/check-out, which are front-desk only).

---

### Edge Cases

- What happens if a staff action is rejected because the role doesn't actually have permission (e.g., a stale UI offered it anyway)? A clear "not allowed" message is shown; nothing crashes, and no action is silently retried as a different role.
- What happens when staff navigate away from a booking's status view before it reaches a final state? No action is required from them — the booking continues to resolve on the backend, and its final state is visible the next time staff check that room or booking.
- What happens if the backend is briefly unreachable while the board is open? A visible retry indicator appears in place of a silent failure or a stuck/frozen board; automatic refreshing resumes once the backend responds again.
- What happens if two staff sessions act on the same room at nearly the same instant (e.g., both try to check the same booking in)? Exactly one succeeds; the other sees the same plain rejection as any other invalid action, and the board corrects itself to the real outcome.
- What happens if staff's session becomes invalid partway through using the app (e.g., expired)? The next action that needs the backend returns them to the login screen rather than failing silently or repeating forever.
- What happens to card details if a booking submission fails partway (e.g., network error before a response arrives)? They are not retried automatically with stored values and are not present anywhere in the app afterward; staff must re-enter them to try again.

## Requirements *(mandatory)*

### Functional Requirements

**Sign-in and session**

- **FR-001**: Staff MUST be able to choose a role (front desk or housekeeping) and sign in before reaching any other screen.
- **FR-002**: Every request the app makes to the backend on a signed-in staff member's behalf MUST carry that session's credentials, scoped to the role chosen at sign-in.
- **FR-003**: Whenever the backend reports that a session's credentials are no longer valid, the app MUST return staff to the login screen.
- **FR-004**: Whenever the backend reports that a signed-in session's role does not permit an attempted action, the app MUST show a plain message explaining the action isn't allowed, without ending the session.
- **FR-005**: Whenever the backend is unreachable or reports itself unavailable, the app MUST show a visible indicator offering staff a way to retry, instead of failing silently.

**Room board**

- **FR-006**: Staff MUST see every room on the property in a single view, grouped by floor and ordered within each floor, each showing its current real status.
- **FR-007**: Any room currently occupied MUST show its current guest's name on the board.
- **FR-008**: The board MUST refresh its data automatically on a fixed, short interval without requiring a manual reload, for as long as it remains open.
- **FR-009**: The board MUST reflect a room's real backend state — including changes made by other staff sessions or by any other means — not only changes made through this session.
- **FR-009a**: The board MUST carry enough information for any signed-in staff member to check in or check out a room directly from it — without needing to have personally created or seen the underlying booking earlier — for both an occupied room and a room with a confirmed booking arriving today.

**Booking a room**

- **FR-010**: Front-desk staff MUST be able to start a new booking for a room shown as vacant and clean, by supplying the guest's name, stay dates, and card details.
- **FR-011**: Housekeeping sessions MUST NOT be offered the ability to start a new booking.
- **FR-012**: After a booking is submitted, staff MUST be shown its progress from its initial held state through to its final outcome (confirmed or cancelled), without needing to take any further action to see that outcome.
- **FR-013**: Card details supplied for a booking MUST be transmitted to the backend and MUST NOT be retained anywhere in the app's own state, browser storage, or any log the app itself produces, at any point before, during, or after submission.

**Check-in and check-out**

- **FR-014**: Front-desk staff MUST be able to check in any room the board shows as having a confirmed booking arriving today, transitioning it to occupied on the board — discoverable directly from the board itself, regardless of which staff session created or confirmed that booking.
- **FR-015**: Front-desk staff MUST be able to check out any room the board currently shows as occupied, transitioning it to dirty on the board — discoverable directly from the board itself, regardless of which staff session originally checked that guest in.
- **FR-016**: Checking a guest out MUST NOT involve any payment, charge, or balance step in the app.
- **FR-017**: Housekeeping sessions MUST NOT be offered the ability to check a guest in or out.

**Room status updates**

- **FR-018**: Staff of either role MUST be able to mark a dirty room clean, and MUST be able to take a clean or dirty room into, and back out of, maintenance.
- **FR-019**: The app MUST NOT offer a status change that the backend does not allow (any change touching an occupied room, or any change outside the allowed set), consistent with the existing room-status rules.

### Key Entities

This layer introduces no new business data — it presents data and transitions that already exist in the backend (room, room status, booking, booking status). The only entity it introduces is the staff member's own browser session:

- **Staff Session**: the signed-in role (front desk or housekeeping) and credentials held by the browser for the duration of its use; determines which actions are offered and permitted, and is discarded on sign-out, expiry, or closing the browser.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A staff member can go from the login screen to seeing the live, correctly grouped room board in under 10 seconds on a normal connection.
- **SC-002**: A status change made anywhere on the backend is visible on an already-open board within one polling interval (10 seconds), with no manual refresh.
- **SC-003**: A front-desk staff member can carry a single room through the entire cycle — book, confirmed, check-in, check-out, marked clean again — using only the app, with no tool or request outside the browser.
- **SC-004**: Across that entire cycle, a card detail entered by staff appears zero times in the app's persisted state, browser storage, or any log the app writes — verified by inspection.
- **SC-005**: Every action the backend refuses for role or state reasons produces a plain, specific on-screen message, never a silent failure, a crash, or a raw technical error.
- **SC-006**: A temporary backend outage is visibly surfaced and automatically recovers once the backend returns, without staff needing to reload the page or sign in again.

## Assumptions

- **Dev-only sign-in, not real authentication**: the role-picker login is a stand-in that obtains a session credential from the existing development identity mock; it is not a production login, has no password or PIN, and does not replace the existing PIN-based device-pairing screens already in the prototype — those remain as-is, unused by this flow, and a real login/PIN backend stays in the backlog.
- **A session lasts for one browser session**: closing the browser (or signing out) ends it; reloading the page may require signing in again rather than silently resuming. No "remember me" or long-lived credential storage is introduced.
- **Only front desk and housekeeping are in scope**: no owner-facing screen, and no third role, is touched by this layer — the existing owner-prototype screens (dashboard, payments, reservations, room management, and the older mock floor plan) are left exactly as they are today and remain on mock data, out of scope for this layer.
- **The room-board response gains two additive fields (confirmed in Clarifications)**: `currentBookingId` on any `OCCUPIED` entry (for check-out), and `arrivingBookingId` plus the guest's name on any entry with a `CONFIRMED` booking whose check-in date is today (for check-in) — both resolved via booking-service's existing data, with no payment-handling change and no existing field altered. This closes the gap that would otherwise have limited check-in/check-out to bookings a given staff session personally remembered creating, and keeps booking-service's test suite at 100% coverage.
- **A booking "arriving today" is scoped to its check-in date, not its confirmation date**: a confirmed booking for a future stay does not appear as checkable-in until the day its stay actually begins — matching how front desk already works in practice, and avoiding the board surfacing arrivals staff can't act on yet.
- **A general staff-facing booking search/list remains out of scope and in the backlog**: this layer only resolves the two specific board-driven cases above (today's arrivals, current occupants); finding any other booking by guest name, date range, or past/future stay still has no screen or endpoint, and stays tracked in BACKLOG.md.
- **Polling, not push**: both the room board and a booking's in-progress status are kept current by polling on a short fixed interval, matching the backend's own synchronous, non-streaming design — no websocket or server push is introduced.
- **No styling or mobile polish**: the existing prototype's visual design is reused as-is where it already fits; this layer is about wiring real data and actions into it, not refining its look or making it responsive for small screens.
- **No charges or balance**: nothing owed beyond the original booking charge is shown or collected anywhere in this layer (layer 2c).
