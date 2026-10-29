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

## Discovered while building the room board (layer 2b)

- **`MAINTENANCE` doesn't check for future `CONFIRMED` bookings**: `PATCH
  /api/v1/rooms/{id}/status` allows a room to be set to `MAINTENANCE` even if it
  already has one or more `CONFIRMED` bookings for future dates. Nothing blocks the
  transition, warns the caller, or reassigns/cancels those bookings — they simply
  stay `CONFIRMED` against a room that is now out of service. Noted explicitly per
  this layer's own planning step; needs a real decision (block the transition?
  surface a warning? reassign automatically?) before it's safe to rely on in
  practice.
