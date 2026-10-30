# Feature Specification: Charges

**Feature Branch**: `011-charges`

**Created**: 2026-10-30

**Status**: Draft

**Input**: User description: "Build layer 2c: Charges, in booking-service. Minimal, B2B staff only. Follow CLAUDE.md working approach. Staff (FRONT_DESK) add charges to a CHECKED_IN booking: type (ROOM_SERVICE, MINIBAR, LAUNDRY, OTHER), description, amount. POST/GET /api/v1/bookings/{id}/charges. Balance due = room nights x nightly rate + charges - amount already paid at booking. GET /api/v1/bookings/{id} returns line items and balance. Check-out charges the balance through the payment core (gRPC, card re-entered by staff at checkout; never stored). Booking CHECKED_IN -> CHECKOUT_PENDING -> CHECKED_OUT on AUTHORIZED; DECLINED keeps the guest checked in with balance still due. Zero balance -> check out immediately, no charge. Board shows a 'balance due' flag on occupied rooms. Frontend: charges panel on an occupied room (add charge, see balance), card form at check-out, and the payment result. Fix root cause: scripts/e2e-test.sh cleanup must only remove containers it started, then remove the CI auth-mock restart workaround. e2e step 15: check in -> add room service -> check out with balance -> CHECKED_OUT, room DIRTY. Extend Playwright smoke test. Out of scope: refunds, split payments, taxes, invoices/PDF, discounts. Log in BACKLOG.md."

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Staff Add a Charge During a Stay (Priority: P1)

A front-desk staff member adds a charge — room service, minibar, laundry, or another
kind — to a guest's current stay, and sees it reflected in that stay's running
balance immediately.

**Why this priority**: Nothing else in this layer has a reason to exist until a
charge can actually be recorded against a stay.

**Independent Test**: Check a booking in, add one or more charges of different
types, and confirm each appears as its own line item with the booking's balance
updated to match.

**Acceptance Scenarios**:

1. **Given** a checked-in booking, **When** staff add a charge with a type,
   description, and amount, **Then** it appears as a line item on that booking and
   the booking's balance due increases by that amount.
2. **Given** a checked-in booking with no charges yet, **When** staff view it,
   **Then** its balance due equals the room cost already paid at booking time minus
   that same amount — zero, since nothing else has happened yet.
3. **Given** a booking that is not checked in (held, confirmed, cancelled, or
   already checked out), **When** staff attempt to add a charge to it, **Then** the
   request is rejected and no charge is recorded.

---

### User Story 2 — Staff See Which Occupied Rooms Have a Balance Due (Priority: P2)

A front-desk or housekeeping staff member looks at the room board and can tell, at a
glance, which occupied rooms have unpaid charges on them.

**Why this priority**: Staff need this to anticipate checkout before the guest is
standing at the desk, but it only becomes meaningful once Story 1 can actually
produce a balance to show.

**Independent Test**: Add a charge to an occupied room's stay and confirm the board
flags that room as having a balance due; confirm an occupied room with no charges
is not flagged.

**Acceptance Scenarios**:

1. **Given** an occupied room whose stay has one or more charges, **When** staff
   view the board, **Then** that room is flagged as having a balance due.
2. **Given** an occupied room whose stay has no charges, **When** staff view the
   board, **Then** that room is not flagged.

---

### User Story 3 — Staff Check Out a Guest With a Balance Due (Priority: P3)

A front-desk staff member checks a guest out whose stay has unpaid charges. They
take the guest's card, the balance is charged through the same payment core every
other charge in this system goes through, and checkout completes only once that
charge is approved.

**Why this priority**: Completes the actual point of charging anything in the first
place — collecting payment for it at departure — but Stories 1-2 already make the
charges themselves useful (visible, trackable) before this exists.

**Independent Test**: Check a booking in, add a charge, attempt checkout, provide a
card the payment core approves, and confirm the booking reaches checked-out and the
room becomes dirty only once that approval happens — never before. Separately,
attempt checkout with a card the payment core declines and confirm the guest remains
checked in with the same balance still due.

**Acceptance Scenarios**:

1. **Given** a checked-in booking with a balance due, **When** staff initiate
   checkout with a card, **Then** the guest remains checked in while that charge is
   being decided — checkout is not yet complete.
2. **Given** a checkout charge the payment core approves, **When** that approval
   arrives, **Then** the booking becomes checked out and its room becomes dirty.
3. **Given** a checkout charge the payment core declines (or fails outright),
   **When** that outcome arrives, **Then** the booking remains checked in, its
   balance due is unchanged, and the room remains occupied — staff can try checkout
   again.
4. **Given** any point in this flow, **When** a card is entered for the checkout
   charge, **Then** it is used for that one charge and never retained anywhere
   afterward.
5. **Given** a checkout charge still being decided, **When** staff attempt to start
   another checkout for the same booking, **Then** the second attempt is rejected —
   only one checkout charge is ever in flight for a given booking at a time.

---

### User Story 4 — Staff Check Out a Guest With No Balance Due (Priority: P4)

A front-desk staff member checks a guest out whose stay accrued no charges beyond
what was already paid at booking. Checkout completes immediately — there is nothing
to charge and no card is needed.

**Why this priority**: The common case once Stories 1-3 exist, but it's the simplest
path and was already how checkout behaved before this layer — this story only makes
the zero-balance condition explicit and confirms it still holds now that a non-zero
balance is possible.

**Independent Test**: Check a booking in, add no charges, check it out, and confirm
it completes immediately — checked out, room dirty — with no card requested and no
payment-core call made.

**Acceptance Scenarios**:

1. **Given** a checked-in booking with no balance due, **When** staff check it out,
   **Then** the booking becomes checked out and the room becomes dirty immediately,
   with no card requested and no charge made.

---

### Edge Cases

- What happens to charges already on a booking if a checkout charge attempt is
  declined and staff retry? They remain exactly as they were — the balance due on
  retry is the same figure, unaffected by the earlier declined attempt.
- What happens if staff add a charge while a checkout charge for that same booking
  is already being decided? Rejected, same as attempting a second checkout —
  charges can only be added to a booking that is fully checked in, not one mid-
  checkout.
- What happens if the payment core never responds to a checkout charge at all (a
  timeout, not a decline)? The booking stays in its mid-checkout state rather than
  silently reverting or silently completing; resolving a stuck case like this needs
  staff attention and is noted as a gap for a later pass (see Assumptions).
- What happens if two checkout attempts for the same booking race each other? At
  most one checkout charge is ever actually in flight for a given booking — the
  loser is rejected outright, not silently dropped or double-charged.
- What happens to a room's status while its booking's checkout charge is being
  decided? It remains occupied — only a final approval moves it to dirty.

## Requirements *(mandatory)*

### Functional Requirements

**Adding charges**

- **FR-001**: Front-desk staff MUST be able to add a charge to a checked-in booking,
  specifying its type (room service, minibar, laundry, or other), a description, and
  an amount.
- **FR-002**: System MUST reject an attempt to add a charge to a booking that is not
  currently checked in, recording nothing.
- **FR-003**: Front-desk or housekeeping staff MUST be able to view every charge
  recorded against a booking, in the order they were added.

**Balance due**

- **FR-004**: System MUST compute a booking's balance due as the amount already
  charged at booking time, plus the total of every charge recorded against it,
  minus the amount already charged at booking time — i.e., the running total of
  charges not yet collected, since the original room cost was already paid at
  booking time.
- **FR-005**: Viewing a booking's details MUST include every charge recorded
  against it and its current balance due.
- **FR-006**: The room board MUST flag an occupied room whose stay has a balance
  due greater than zero, and MUST NOT flag an occupied room whose stay has none.

**Checking out with a balance**

- **FR-007**: Front-desk staff MUST be able to check out a checked-in booking whose
  balance due is greater than zero by supplying a card; the room remains occupied
  and the booking remains checked in while that charge is being decided.
- **FR-008**: The checkout charge MUST be submitted through the same payment core
  every other charge in this system goes through, for exactly the booking's current
  balance due.
- **FR-009**: System MUST reject a second checkout attempt for a booking whose
  previous checkout charge has not yet been decided.
- **FR-010**: When a checkout charge is approved, system MUST transition the
  booking to checked out and its room to dirty.
- **FR-011**: When a checkout charge is declined or fails, system MUST leave the
  booking checked in and its balance due unchanged, so staff can attempt checkout
  again.
- **FR-012**: A card supplied for a checkout charge MUST be transmitted for that
  charge only and MUST NOT be stored anywhere, at any point before, during, or
  after the charge is decided.

**Checking out with no balance**

- **FR-013**: Front-desk staff MUST be able to check out a checked-in booking whose
  balance due is zero without supplying a card and without any payment-core call
  being made; the booking becomes checked out and its room becomes dirty
  immediately.

**Staff interface**

- **FR-014**: Staff MUST be able to add a charge and see the resulting balance from
  the same screen used to manage an occupied room.
- **FR-015**: Staff MUST be presented with a way to supply a card when checking out
  a booking with a balance due, and MUST be shown the outcome of that charge
  (approved or declined) once it is known.
- **FR-016**: Front-desk staff MUST be able to check out a booking with no balance
  due directly, without the card step appearing at all.

### Key Entities

- **Charge**: one recorded item against a specific booking — its type (room
  service, minibar, laundry, or other), a free-text description, an amount, and
  when it was added. Charges are never edited or removed once added (no reversal,
  refund, or correction flow exists in this layer).
- **Booking** *(extended)*: gains a mid-checkout state, reached only from checked-in
  and left only by resolving the checkout charge it represents (to checked-out on
  approval, or back to checked-in on decline/failure) — mirroring how the
  booking's original HELD→charge→confirmed/cancelled lifecycle already works, now
  repeated once more at the other end of the stay.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A charge added to a checked-in booking is reflected in that booking's
  balance due within the same request/response cycle — no polling or delay
  required to see it.
- **SC-002**: A room's "balance due" flag on the board always matches whether that
  room's current stay actually has an outstanding balance, with no false positives
  or false negatives observed across check-in, charges, and checkout.
- **SC-003**: A guest's checkout never completes while a balance is outstanding and
  undecided — a booking is checked out if and only if its balance was ultimately
  either zero or successfully charged.
- **SC-004**: A card entered for a checkout charge appears zero times in any stored
  state, anywhere in the system, after that charge is decided — matching the same
  standard already held for a card entered at booking time.
- **SC-005**: Staff can complete the full cycle — check in, add a charge, check out
  with that balance, reach checked-out — using only the staff interface, with no
  tool outside it.

## Assumptions

- **The original room cost is charged at booking time, not at checkout**: this
  project already charges a booking's full room-nights cost when the booking is
  first confirmed (layer 2a). This layer's "balance due" is therefore, in practice,
  simply the running total of charges not yet collected — the general formula in
  FR-004 is kept exact and explicit anyway, so it stays correct even if a future
  layer changes how or when the original room cost is charged.
- **No automatic recovery for a checkout charge that never gets an outcome**: a
  booking's original HELD state has an existing automatic hold-expiry sweep for
  exactly this situation; the new mid-checkout state introduced here has no
  equivalent in this layer. A checkout charge that times out or is otherwise never
  resolved leaves that booking stuck mid-checkout until a staff member or a future
  pass addresses it — logged in BACKLOG.md rather than built now, consistent with
  not over-building a rare failure path before it's shown to matter in practice.
- **No refunds, split payments, taxes, discounts, or invoices/PDFs**: a charge is a
  flat amount of a fixed type; nothing in this layer computes tax, applies a
  discount, splits a balance across multiple cards, reverses a charge, or produces
  any printable/exportable document.
- **Charges are add-only**: there is no way to edit or remove a charge once added,
  by design — a correction mechanism is out of scope for this layer.
- **The e2e test harness's own auth-mock handling gets a correctness fix, not a new
  feature**: `scripts/e2e-test.sh`'s cleanup currently always removes a container
  named `auth-service` regardless of whether it was standing there before the
  script ran (a gap that forced a workaround in CI after the previous layer). This
  layer fixes that at the root — cleanup restores whatever was there before instead
  of just deleting it — and removes the now-unnecessary CI workaround. This is a
  test-harness correctness fix, not user-facing behavior, and has no functional
  requirement of its own above.
