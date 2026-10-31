<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the current plan
at specs/011-charges/plan.md
<!-- SPECKIT END -->


## How to work with me
I want to learn the codebase, not just get changes. For every task:
- Before changing anything, list the files you'll touch and why.
- Explain each change in plain terms: what it does, why it's needed, and what would break without it.
- Show me the exact commands you run and what each one does.
- After finishing, give a per-file summary and point me to the single most important change to read first.
- Prefer small, separate changes over one large one.
- Approval granularity: for `/speckit-tasks`-generated work, approve per **phase**
  (Setup / Foundational / each user story / Polish), not per individual task. Implement
  a whole phase, commit it, then stop and show the checkpoint for that phase before
  moving to the next. This supersedes the constitution's Principle IV at the
  method/class level for the duration of a phase; the phase boundary is still a hard
  stop.

## Working approach
- Make it operable first with the minimum needed; add requirements and security
  hardening in later passes, one layer at a time.
- Every layer must run end to end (docker-compose + CI + e2e) before the next starts.
- No speculative features. If something isn't in the current layer's spec, log it in
  docs/BACKLOG.md instead of building it.
- Payments are the core. Hotel layers never touch card data; they only ask the payment
  core to charge and react to the result. Do not change payment services unless a
  layer's spec requires it.
- B2B: users are hotel staff (front desk, housekeeping, owner), not guests.
- Fix root causes, not retries. Keep existing conventions (schema per service, Flyway,
  idempotent consumers, event envelope, 100% JaCoCo).
- Backend-heavy: all business logic, validation, calculations (balances, occupancy,
  counts), state transitions, auth checks, and permissions live in backend services.
  The frontend only renders API responses and submits user input. Hiding UI by role is
  cosmetic; every rule is enforced server-side. No business logic in React components
  or Redux.
- Each layer gets its own branch off main (NNN-layer-name).

## Roadmap (layers on top of the payment core)
- 2a Booking (done): rooms, availability search, staff create booking for a guest,
  HELD -> charge -> CONFIRMED/CANCELLED saga, no double booking, 10-min hold expiry.
- 2b Room board (done): room status (VACANT_CLEAN, OCCUPIED, DIRTY, MAINTENANCE),
  floor and position (configurable, default 6 rooms per floor), staff update status,
  checkout sets DIRTY.
- 2d Frontend MVP (done): staff floor-plan grid colored by status.
- 2c Charges (in progress, branch `011-charges`): extras on a booking (room service
  etc.), balance due, charge balance at checkout via the payment core. Revised:
  richer guest details captured at booking (phone, email, visiting from, adults,
  children, expected arrival/departure times, notes); no card charged at booking
  any more -- the booking goes CONFIRMED immediately with no payment saga, and all
  payment (room cost + any extras) is collected only at checkout. Next:
  `/speckit-clarify` this revision into the existing spec, then plan -> tasks ->
  implement phase by phase with checkpoints -> PR -> CI green. Do not merge
  without explicit approval.
- 2e Staff auth + dashboard (specified, branch `012-staff-auth-dashboard`, spec not
  yet re-clarified/planned -- **blocked until 2c merges to main**, then rebase
  012 onto main first): Toast-style device + PIN staff sign-in replacing the dev
  auth-mock, manager staff management, dashboard home, reservations search. Will
  be re-clarified before planning with the fuller sign-in design: a
  Customer/Staff landing split, Admin/Worker roles, first sign-in by email ->
  set a PIN -> confirm it, every sign-in after that by profile tile + PIN,
  lockout, no stored passwords beyond that one first-time flow, and a public
  customer landing page showing availability/prices only (no booking yet --
  that's 2f).
- 2f Customer self-service booking (not yet specified, planned branch
  `013-customer-booking`, **blocked until 2e merges to main**): a public,
  unauthenticated booking endpoint (no login, no PIN, no payment at booking
  time -- payment stays at checkout), backend abuse limits (per-IP rate limit,
  max stay length, max days ahead), staff visibility of ONLINE vs FRONT_DESK
  bookings (source badge on the board/dashboard/reservations, full guest
  details), and front-desk/manager cancellation of a not-yet-checked-in online
  booking. User will resend the full feature description when this starts.
- Later passes (backlog): Vault production mode, k8s/GKE wiring for new
  services, refunds, pricing rules, multi-property.