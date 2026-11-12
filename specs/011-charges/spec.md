# Feature Specification: Charges

**Feature Branch**: `011-charges`

**Created**: 2026-10-30

**Status**: Draft

**Input**: User description: "Build layer 2c: Charges, in booking-service. Minimal, B2B staff only. Follow CLAUDE.md working approach. Staff (FRONT_DESK) add charges to a CHECKED_IN booking: type (ROOM_SERVICE, MINIBAR, LAUNDRY, OTHER), description, amount. POST/GET /api/v1/bookings/{id}/charges. Balance due = room nights x nightly rate + charges - amount already paid at booking. GET /api/v1/bookings/{id} returns line items and balance. Check-out charges the balance through the payment core (gRPC, card re-entered by staff at checkout; never stored). Booking CHECKED_IN -> CHECKOUT_PENDING -> CHECKED_OUT on AUTHORIZED; DECLINED keeps the guest checked in with balance still due. Zero balance -> check out immediately, no charge. Board shows a 'balance due' flag on occupied rooms. Frontend: charges panel on an occupied room (add charge, see balance), card form at check-out, and the payment result. Fix root cause: scripts/e2e-test.sh cleanup must only remove containers it started, then remove the CI auth-mock restart workaround. e2e step 15: check in -> add room service -> check out with balance -> CHECKED_OUT, room DIRTY. Extend Playwright smoke test. Out of scope: refunds, split payments, taxes, invoices/PDF, discounts. Log in BACKLOG.md."

## Clarifications

### Session 2026-10-30

- Q: A checkout payment attempt that never gets an outcome (payment-core timeout)
  was left as a backlog gap — the booking stuck mid-checkout until a staff member
  or a future pass addresses it. Should this instead be recoverable automatically
  now, and if so, how? → A: Yes, recoverable now, reusing the existing hold-expiry
  sweep's own mechanism and window (10 minutes) rather than building a new one:
  a checkout payment attempt older than that reverts the booking to checked-in
  automatically, balance unchanged, so staff can retry. A late outcome for an
  attempt that already reverted this way is never silently dropped: a late
  approval is recorded as a payment against the booking (reducing its balance due
  by that attempt's amount — if that brings the balance to zero, staff can then
  check out with no further charge), and a late decline or failure is a no-op.
  Exactly one checkout payment attempt is ever in flight for a given booking at a
  time — a second attempt while one is already in flight is rejected outright.

### Session 2026-10-31

- Q: Should a booking's room cost still be charged at booking time (layer 2a's
  original behavior), or only at checkout from now on? → A: Only at checkout.
  No card is requested or taken when a booking is created; the booking is
  confirmed immediately (the only remaining reason a submission can be rejected
  is a room/date conflict, same as always, or a missing required field). The
  room's cost for the stay is added to its balance due the moment it's
  confirmed, collected later through the exact same checkout-payment machinery
  already built for extras in this layer.
- Q: Beyond name and stay dates, which of the richer guest-detail fields (phone,
  email, visiting from, adults/children, expected arrival/departure times,
  notes) must staff fill in vs. may leave blank? → A: Name, phone, and both
  stay dates are required. Email, visiting-from, notes, and both expected
  times are optional. Adults/children, if left blank, default to one adult and
  zero children respectively.
- Q: Is "room sheet" the existing occupied-room detail panel staff already use
  for charges/checkout, extended with these new fields — or a new, separate
  screen? → A: The existing panel, extended. No new screen. Applies equally to
  an arriving booking's own panel (shown before check-in, today's existing
  check-in action) — staff see the full guest record there too, not only once
  a guest is checked in.

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Staff Capture a Full Guest Record and Confirm the Booking Immediately (Priority: P1)

A front-desk staff member books a room for a guest standing at the desk (or
calling), recording their name, phone number, and the stay's dates — plus
whatever else the guest volunteers (email, where they're visiting from, how many
adults and children, expected arrival/departure times, notes) — and the booking is
confirmed on the spot. No card is asked for or taken at this step; the guest pays
only when they check out.

**Why this priority**: Every other story in this layer operates on a confirmed —
and eventually checked-in — booking; none of it is reachable until booking
creation itself works this way.

**Independent Test**: Submit a booking with only a guest's name, phone, and stay
dates — the minimum required — and confirm it's created and confirmed
immediately, with no card requested anywhere and no payment-core call made.
Separately, submit one with every optional field filled in too, and confirm all
of it is recorded and later visible to staff, both before and after check-in.

**Acceptance Scenarios**:

1. **Given** an available room and a guest's name, phone, and stay dates, **When**
   staff submit a booking with nothing else filled in, **Then** the booking is
   created and confirmed immediately, with no card requested and no payment-core
   call made.
2. **Given** the same information plus the guest's email, where they're visiting
   from, adult and child counts, expected arrival/departure times, and notes,
   **When** staff submit it, **Then** every one of those details is recorded
   against the booking.
3. **Given** a booking submission missing a guest's name, phone number, or either
   stay date, **When** staff submit it, **Then** it is rejected and nothing is
   created.
4. **Given** a booking submission that leaves out adult or child counts, **When**
   staff submit it, **Then** it defaults to one adult and zero children.
5. **Given** two submissions for the same room and overlapping dates, **When** the
   second one arrives, **Then** it is rejected the same way double-booking has
   always been rejected — this check is unrelated to payment, since none happens
   at this step any more.
6. **Given** a newly confirmed booking, **When** staff view it — whether it's
   still awaiting check-in or already checked in — **Then** every guest detail
   submitted for it is visible, along with the balance now due for the full stay
   (starting at the room's cost for those nights, since nothing has been paid
   yet).

---

### User Story 2 — Staff Add a Charge During a Stay (Priority: P2)

A front-desk staff member adds a charge — room service, minibar, laundry, or another
kind — to a guest's current stay, and sees it reflected in that stay's running
balance immediately.

**Why this priority**: Nothing in this story is reachable until a booking exists
and is checked in (Story 1) — but once it is, recording extras against it is the
next-most basic thing this layer does.

**Independent Test**: Check a booking in, add one or more charges of different
types, and confirm each appears as its own line item with the booking's balance
updated to match.

**Acceptance Scenarios**:

1. **Given** a checked-in booking, **When** staff add a charge with a type,
   description, and amount, **Then** it appears as a line item on that booking and
   the booking's balance due increases by that amount.
2. **Given** a checked-in booking with no charges yet, **When** staff view it,
   **Then** its balance due equals the room's cost for the stay — nothing has been
   paid toward it yet, and no charges have been added either.
3. **Given** a booking that is not checked in (confirmed but not yet arrived,
   cancelled, or already checked out), **When** staff attempt to add a charge to
   it, **Then** the request is rejected and no charge is recorded.

---

### User Story 3 — Staff See Which Occupied Rooms Have a Balance Due (Priority: P3)

A front-desk or housekeeping staff member looks at the room board and can tell, at a
glance, which occupied rooms have unpaid charges on them.

**Why this priority**: Staff need this to anticipate checkout before the guest is
standing at the desk, but it only becomes meaningful once Story 2 can actually
produce a balance to show beyond the room cost every stay already carries.

**Independent Test**: Add a charge to an occupied room's stay and confirm the board
flags that room as having a balance due; confirm an occupied room with no charges
is not flagged.

**Acceptance Scenarios**:

1. **Given** an occupied room whose stay has one or more charges, **When** staff
   view the board, **Then** that room is flagged as having a balance due.
2. **Given** an occupied room whose stay has no charges, **When** staff view the
   board, **Then** that room is not flagged.

---

### User Story 4 — Staff Check Out a Guest With a Balance Due (Priority: P4)

A front-desk staff member checks a guest out whose stay has unpaid charges. They
take the guest's card, the balance is charged through the same payment core every
other charge in this system goes through, and checkout completes only once that
charge is approved.

**Why this priority**: Completes the actual point of charging anything in the first
place — collecting payment for it at departure — but Stories 2-3 already make the
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
6. **Given** a checkout charge that receives no outcome within the same window the
   existing hold-expiry check already uses, **When** that window passes, **Then**
   the booking automatically returns to checked in, its balance due unchanged, so
   staff can attempt checkout again.
7. **Given** a checkout charge that reverted this way, **When** an approval for it
   arrives later anyway, **Then** it is recorded as a payment that reduces the
   booking's balance due by that charge's amount — never silently discarded — and
   if that brings the balance to zero, staff can check that guest out with no
   further charge.
8. **Given** a checkout charge that reverted this way, **When** a decline or
   failure for it arrives later, **Then** nothing changes.

---

### User Story 5 — Staff Check Out a Guest With No Balance Due (Priority: P5)

A front-desk staff member checks a guest out whose stay truly cost nothing — a
comped room at a zero rate, with no charges added either. Checkout completes
immediately — there is nothing to charge and no card is needed.

**Why this priority**: Lowest priority of all, because it's now a narrow edge
case rather than the common one: since a stay's room cost is no longer paid at
booking (Story 1), almost every booking carries some balance due until checkout.
This story exists only so that rare zero-balance case still works correctly once
a non-zero balance is the norm.

**Independent Test**: Check in a booking for a room with a zero nightly rate, add
no charges, check it out, and confirm it completes immediately — checked out,
room dirty — with no card requested and no payment-core call made.

**Acceptance Scenarios**:

1. **Given** a checked-in booking with a balance due of zero (a zero-rate room
   with no charges added), **When** staff check it out, **Then** the booking
   becomes checked out and the room becomes dirty immediately, with no card
   requested and no charge made.

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
  timeout, not a decline)? It automatically reverts to checked-in after the same
  window the hold-expiry check already uses, rather than staying stuck
  indefinitely — see the Clarifications resolution above.
- What happens if a late approval arrives for a reverted checkout charge, but the
  booking has since been checked out some other way entirely (e.g., a later
  checkout attempt already succeeded, or a zero-balance checkout already
  completed)? The payment is still recorded, never silently dropped — this is now
  an overpayment needing a human to notice and refund, the same situation the
  original booking saga already has for a late-arriving authorization after a
  hold already expired.
- What happens if two checkout attempts for the same booking race each other? At
  most one checkout charge is ever actually in flight for a given booking — the
  loser is rejected outright, not silently dropped or double-charged.
- What happens to a room's status while its booking's checkout charge is being
  decided? It remains occupied — only a final approval moves it to dirty.
- What happens if staff submit a booking with everything required but leave out
  adults/children entirely? It defaults to one adult and zero children — not
  rejected.
- What happens if two different rooms are booked for the same guest's same stay,
  or the same room is booked twice for genuinely non-overlapping dates? Neither
  is a conflict — the only rejection is the existing same-room, overlapping-dates
  check, unrelated to the guest's identity.
- What happens to a booking's balance due the moment it's confirmed, before
  anything else happens to it? It already equals the room's full cost for the
  stay — never zero, unless the room's rate is itself zero.

## Requirements *(mandatory)*

### Functional Requirements

**Booking with full guest details, no card**

- **FR-017**: System MUST let front-desk staff create a booking by submitting a
  guest's name, phone number, and the stay's check-in and check-out dates — no
  card, of any kind, at this step.
- **FR-018**: Staff MAY additionally submit the guest's email, the place they're
  visiting from, the number of adults and children staying, expected arrival and
  departure times, and free-text notes; leaving the adult or child count out
  defaults it to one adult and zero children respectively — leaving any of the
  other optional fields out simply records nothing for them.
- **FR-019**: System MUST reject a booking submission missing a guest's name,
  phone number, or either stay date, recording nothing.
- **FR-020**: A booking MUST be confirmed immediately upon creation — no card is
  requested, no payment-core call is made, and no intermediate "awaiting
  payment" state is ever shown to staff. The only reasons a submission can still
  be rejected are a room/date conflict (the same check that already existed) or
  a missing required field (FR-019).
- **FR-021**: The room's cost for the stay, computed at the rate in effect when
  the booking is made, MUST be added to that booking's balance due the moment it
  is confirmed — none of it is collected at booking time; all of it, like every
  other charge, is collected only at checkout (see Balance due, below).
- **FR-022**: System MUST NOT write a guest's phone number, email, or notes to
  any log entry, in any form — the same standard already held for card data.

**Adding charges**

- **FR-001**: Front-desk staff MUST be able to add a charge to a checked-in booking,
  specifying its type (room service, minibar, laundry, or other), a description, and
  an amount.
- **FR-002**: System MUST reject an attempt to add a charge to a booking that is not
  currently checked in, recording nothing.
- **FR-003**: Front-desk or housekeeping staff MUST be able to view every charge
  recorded against a booking, in the order they were added.

**Balance due**

- **FR-004**: System MUST compute a booking's balance due as its room cost
  (FR-021) plus the total of every charge recorded against it, minus every
  checkout payment successfully recorded against it so far (FR-010, FR-012b) —
  since no part of a stay is paid until checkout now, a freshly confirmed
  booking's balance due starts at its full room cost and only ever decreases
  from there as checkout payments are recorded.
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
- **FR-012a**: A checkout charge that receives no outcome within the same window
  the existing hold-expiry check already uses MUST automatically return its
  booking to checked in, leaving its balance due unchanged, so staff can attempt
  checkout again.
- **FR-012b**: An approval that arrives for a checkout charge after it has already
  reverted this way MUST be recorded as a payment reducing the booking's balance
  due by that charge's amount — regardless of the booking's state at that point —
  never silently discarded. A decline or failure that arrives after reverting
  MUST be a no-op.
- **FR-012c**: Once a late-recorded payment (FR-012b) brings a booking's balance
  due to zero, staff MUST be able to check that booking out with no further
  charge, the same as any other zero-balance checkout.

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
- **FR-023**: Staff MUST be able to see every guest detail recorded for a
  booking — name, phone, and whichever optional fields were supplied — from the
  same room panel they already use for charges and checkout, both for a room
  awaiting an arriving guest's check-in and for one already occupied.

### Key Entities

- **Charge**: one recorded item against a specific booking — its type (room
  service, minibar, laundry, or other), a free-text description, an amount, and
  when it was added. Charges are never edited or removed once added (no reversal,
  refund, or correction flow exists in this layer).
- **Booking** *(extended)*: no longer passes through a payment-pending state at
  creation at all — it is confirmed the instant it's accepted, with its guest's
  name, phone number, and (if supplied) email, visiting-from, adult/child counts,
  expected arrival/departure times, and notes recorded against it from that
  moment on. It still gains the mid-checkout state this layer always added,
  reached only from checked-in and left only by resolving the checkout charge it
  represents (to checked-out on approval, or back to checked-in on decline/
  failure, or automatically back to checked-in if nothing is heard within the
  hold-expiry window) — that part of its lifecycle is unchanged by this revision,
  since checkout payment was never tied to the booking-time saga this revision
  removes.
- **Checkout Payment Attempt**: one attempt to charge a booking's balance at
  checkout — the amount attempted and its outcome (still deciding, approved,
  declined/failed, or timed out with no answer heard). A booking can accumulate
  more than one of these over its lifetime (a timed-out or declined attempt
  followed later by a successful one); an approved attempt, whenever its outcome
  actually arrives, always reduces the booking's balance by that attempt's
  amount.

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
- **SC-004**: No card is ever requested, transmitted, or stored at booking time
  at all — the only point a card is ever involved is checkout, and even there it
  appears zero times in any stored state after that charge is decided.
- **SC-005**: Staff can complete the full cycle — book a guest with their name,
  phone, and stay dates, check them in, add a charge, check out with that
  balance, reach checked-out — using only the staff interface, with no tool
  outside it and no card involved before checkout.
- **SC-006**: A checkout charge that never receives an outcome does not
  permanently block its booking — it recovers automatically within the same
  window the existing hold-expiry check uses, every time. A late outcome for it,
  whenever it eventually arrives, is always accounted for — approved outcomes
  always reduce the balance by that attempt's amount, and this never happens
  twice for the same attempt.
- **SC-007**: A booking can be created with nothing more than a guest's name,
  phone number, and stay dates, confirmed immediately — no card, no payment-core
  call, no intermediate pending state, at that step.
- **SC-008**: A guest's phone number, email, or notes appear zero times in any
  log entry, ever.

## Assumptions

- **The room cost moves from booking time to checkout time**: this revises layer
  2a's original behavior, which charged a booking's full room-nights cost via the
  payment core at booking time. From this layer forward, a booking's room cost is
  only ever collected at checkout, through the same balance-due/checkout-payment
  machinery already built here for extras (FR-004, FR-021) — a booking's balance
  due is therefore the room cost plus every charge, not just "charges not yet
  collected" as this layer first assumed.
- **A zero balance due is now the rare case, not the common one**: since the room
  cost itself is part of the balance due until checkout, a zero-balance checkout
  (Story 5) only happens for a stay with a zero room rate and no charges added —
  e.g., a comped stay. Every ordinary booking carries a balance due from the
  moment it's confirmed until it's settled at checkout.
- **Adults/children default sensibly when omitted**: a submission that leaves
  these out is treated as one adult and zero children, not rejected.
- **Expected arrival/departure times are informational only**: they're recorded
  and shown to staff for planning purposes, but nothing in this layer enforces,
  validates against, or automatically acts on them — no early/late check-in fee,
  no automatic hold tied to a stated arrival time.
- **No email or SMS confirmation is sent for a new booking**: staff see it
  confirmed on screen immediately; notifying the guest some other way is out of
  scope here (see `docs/BACKLOG.md`).
- **The checkout-charge recovery sweep reuses the existing hold-expiry mechanism
  and window**: rather than introducing a second, separately-tuned timeout, a
  stuck checkout charge is swept on the same schedule and after the same 10
  minutes the original hold-expiry check already uses (Clarifications above) —
  one scheduled mechanism covering both cases, not two to keep in sync.
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
