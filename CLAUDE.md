<!-- SPECKIT START -->
For additional context about technologies to be used, project structure,
shell commands, and other important information, read the current plan
at specs/008-booking-service/plan.md
<!-- SPECKIT END -->


## How to work with me
I want to learn the codebase, not just get changes. For every task:
- Before changing anything, list the files you'll touch and why.
- Explain each change in plain terms: what it does, why it's needed, and what would break without it.
- Show me the exact commands you run and what each one does.
- After finishing, give a per-file summary and point me to the single most important change to read first.
- Prefer small, separate changes over one large one.

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

## Roadmap (layers on top of the payment core)
- 2a Booking (current): rooms, availability search, staff create booking for a guest,
  HELD -> charge -> CONFIRMED/CANCELLED saga, no double booking, 10-min hold expiry.
- 2b Room board: room status (VACANT_CLEAN, OCCUPIED, DIRTY, MAINTENANCE), floor and
  position (configurable, default 6 rooms per floor), staff update status,
  checkout sets DIRTY.
- 2c Charges: extras on a booking (room service etc.), balance due, charge balance at
  checkout via the payment core.
- 2d Frontend: staff floor-plan grid colored by status (existing React prototype).
- Later passes (backlog): security hardening (real auth server, Vault prod mode,
  PIN auth backend), k8s/GKE wiring for new services, refunds, pricing rules,
  multi-property.