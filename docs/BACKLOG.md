# Backlog

Scope deliberately deferred rather than built, per CLAUDE.md's Working approach
("if something isn't in the current layer's spec, log it here instead of building
it"). Not a roadmap of planned work with dates — a record of what was consciously
left out, and why, so it isn't quietly forgotten or quietly reinvented.

## Carried from the Roadmap (CLAUDE.md)

These were named up front as out of scope for every layer until explicitly started:

- **Security hardening**: a real auth server (the current JWT issuer is a
  throwaway e2e-test-only mock), Vault production mode (currently dev mode,
  in-memory, single unseal key), a real PIN-auth backend for front-desk login.
- **Kubernetes/GKE wiring for new services** (`booking-service` included): no
  `k8s/base` manifests, no kind/GKE overlay entries. `booking-service` today
  only runs via `docker-compose.yml`; `scripts/e2e-test.sh --target kind` skips
  booking steps 12-13 for this reason (see `step12_booking_confirmed`/
  `step13_booking_declined`'s kind-mode guards).
- **Refunds**: no automated refund flow exists anywhere in the payment core or
  `booking-service`. The one case that needs one today — a late `AUTHORIZED`
  outcome arriving after a booking's hold already expired (FR-019) — only logs
  a WARN for a human to action manually; nothing pages or alerts on it yet.
- **Pricing rules**: room rates are flat, Flyway-seeded, and static. No
  seasonal/dynamic pricing, taxes, fees, or discounts.
- **Multi-property**: room inventory assumes a single property; no property
  entity or scoping exists.

## Discovered while building `booking-service` (layer 2a)

- **Single currency, hardcoded**: `BookingService` hardcodes `currencyCode =
  "USD"` on every `InitiateTransaction` call. Not in the original spec's scope
  (Assumptions: "no multi-currency"), but worth naming explicitly now that it's
  a literal constant rather than an assumption, in case a later layer needs it.
- **No staff-facing booking list/search**: only `GET /api/v1/bookings/{id}`
  (by id) and `GET /api/v1/rooms` (availability) exist — there's no "list
  today's bookings" or "search bookings by date/guest/status" endpoint. Will
  likely be needed once the 2d frontend layer (staff floor-plan grid) needs to
  render more than a single booking at a time.
- **No staff override on a CONFIRMED booking**: the current spec intentionally
  has no cancellation endpoint at all (self-service or staff-initiated) — a
  CONFIRMED booking cannot be changed or cancelled through this API by anyone,
  staff included. Not addressed by any current or near-term layer; flagging so
  it isn't assumed to exist when 2b (room status)/2c (charges) start touching
  booking lifecycle again.
- **No pagination on `GET /api/v1/rooms`**: fine at the current 14-room seed
  size; would need it before room inventory grows meaningfully.

## Carried from the room-board (layer 2b) work

- **The other 7 services' integration tests still use a generic Postgres
  Testcontainer, not CockroachDB**: `booking-service`'s own integration tests were
  switched to a CockroachDB Testcontainer (`CockroachTestContainer`, a plain
  `GenericContainer` on the exact image docker-compose uses — no official
  `testcontainers-cockroachdb` module exists on this project's Testcontainers 2.x
  line) after a real CockroachDB-specific schema bug (a bare `INT` column defaulting
  to 8-byte `INT8`, which plain Postgres's 4-byte `INT` never would have surfaced)
  only showed up on a live docker-compose run, not in any test. The same class of
  bug is equally possible in any of the other 7 services, whose integration tests
  still run against generic Postgres containers. Not fixed here — flagging the
  pattern (and the new `booking-service`-local `CockroachTestContainer` helper as a
  starting point) for whoever picks this up for the rest of the platform.

## Discovered while building the room board (layer 2b)

- **`MAINTENANCE` doesn't check for future `CONFIRMED` bookings**: `PATCH
  /api/v1/rooms/{id}/status` allows a room to be set to `MAINTENANCE` even if it
  already has one or more `CONFIRMED` bookings for future dates. Nothing blocks the
  transition, warns the caller, or reassigns/cancels those bookings — they simply
  stay `CONFIRMED` against a room that is now out of service. Noted explicitly per
  this layer's own planning step; needs a real decision (block the transition?
  surface a warning? reassign automatically?) before it's safe to rely on in
  practice.

## Carried from the Frontend MVP (layer 2d) work

- **Replace dev login with real auth**: the `/staff` area's role-picker login
  (`LoginScreen`/`staffAuthApi.ts`) and the `auth-mock` service it calls
  (`scripts/dev-auth-mock.py`) are a deliberate dev-only stand-in — no password,
  PIN, or real identity check of any kind; anyone can request a token for either
  role. Both exist only in `docker-compose.yml` and CI, never in `k8s/` or any
  deployed environment (see SECURITY.md). Replacing this with the project's real
  auth server (already named as a "later pass" in CLAUDE.md's Roadmap) is required
  before this frontend could ever be exposed to anyone but a developer or CI.

## Carried from the charges / checkout-only-payment rework (layer 2c)

- **No refunds, split payments, taxes, discounts, or invoices/PDFs**: a charge
  is a flat amount of a fixed type; nothing in this layer computes tax, applies
  a discount, splits a balance across multiple cards, reverses a charge, or
  produces any printable/exportable document (spec.md Assumptions).
- **No email or SMS confirmation sent for a new booking**: staff see it
  confirmed on screen immediately; notifying the guest some other way is out
  of scope here (spec.md Assumptions).
- **Charges are add-only**: there is no way to edit or remove a charge once
  added, by design — a correction mechanism is out of scope for this layer.
- **`CancellationReason`'s four pre-rework values are dormant**:
  `PAYMENT_DECLINED`/`PAYMENT_FAILED`/`HOLD_EXPIRED`/`PAYMENT_AFTER_EXPIRY`
  can no longer be produced by anything in this layer (their only source, the
  booking-time payment saga, was removed) — kept, not deleted, because layer
  2f's planned staff-cancellation feature is expected to reuse the
  `CANCELLED` status and this enum rather than reintroducing them. If 2f ships
  without ever touching this enum, these four values should be revisited.

## Carried from 013 (transaction PENDING timeout)

- **booking-service startup check for timer rule 2**: at startup, booking-service must check
  `P + m + d <= S` (300 + 60 + 5 <= 600 with the current values) against its own
  `BOOKING_CHECKOUT_TIMEOUT_MINUTES`, and refuse to start if it fails. `TimerOrdering.checkBookingWindow`
  exists in transaction-service but is not wired into booking-service. Deferred from 013 phase 2 on
  owner instruction; the values in use satisfy the rule today (design.md, Timer rules).

## Spec 014 phase 2 deferrals

- **Unresolved VOID retry**: when a VOID and its status lookup are both unresolved, the hold stays and `RELEASE_REJECTED` (`VOID_UNKNOWN`) is recorded with no retry. Needs a retry path (deterministic VOID ID makes it safe to repeat).
- **Committed test TLS key**: `libs/mastercard-gateway/src/testFixtures/resources/test-keystore.p12` is a self-signed test-only key committed to the repo. Move it to generation at test time or confirm it may stay.
