# Phase 0 Research: Booking Service

## 1. Double-booking prevention under concurrency

**Decision**: A dedicated `booking_room_nights` join table with one row per
`(room_id, stay_date)` and a `UNIQUE (room_id, stay_date)` constraint. Creating a HELD
booking inserts one row per night of the stay (checkIn inclusive, checkOut exclusive) in
the same CockroachDB transaction that inserts the `bookings` row. A unique-constraint
violation on any of those inserts means the room is already held/confirmed for that
night; the whole transaction rolls back and the request is rejected as unavailable.

**Rationale**: CockroachDB only offers SERIALIZABLE isolation (no weaker level to fall
back to), so a naive `SELECT ... FOR UPDATE` plus an application-level "is it free"
check is still exposed to serialization conflicts under concurrent writers and requires
an explicit retry loop on `40001` (serialization failure) to be correct. A unique
constraint turns the same guarantee into a single, cheap, race-free DB-enforced rule:
the first of two concurrent inserts wins, the second fails fast with a constraint
violation that maps directly to "room unavailable" — no retry loop needed, and it is the
simplest thing that is still fully correct (consistent with CLAUDE.md's "operable first
with the minimum needed").

**Alternatives considered**:
- `SELECT ... FOR UPDATE` on a per-room availability row, then insert — rejected: still
  needs explicit `40001` retry handling under SERIALIZABLE, and adds a lock-management
  surface the unique-constraint approach doesn't need.
- A single `(room_id, date_range)` exclusion constraint — rejected: CockroachDB has no
  native range-overlap exclusion constraint (unlike PostgreSQL's `EXCLUDE USING gist`);
  the per-night row approach is the portable equivalent and also gives
  `GET /api/v1/rooms` a trivial availability query (`NOT EXISTS` any night in range).

**Cleanup on cancellation**: the mirror image of the insert must be just as atomic. Every
path that cancels a booking (declined/failed payment, hold expiry) deletes that
booking's `booking_room_nights` rows in the **same** CockroachDB transaction as the
`status`/`cancellation_reason` update — never as a separate step, and never left to a
background sweep. Rejected alternative: a nightly cleanup job to garbage-collect rows for
cancelled bookings — rejected because it would leave a window (however short) where a
cancelled booking still blocks the room, directly contradicting SC-003/SC-004's "within a
few seconds" / "every time" guarantees.

## 2. gRPC client shape for calling `transaction-service`

**Decision**: `TransactionServiceGrpc.TransactionServiceBlockingStub`, configured the
same way as `api-gateway`'s `GrpcClientConfig` (`@GrpcClient("transaction-service")`
channel bean), but exposing a **blocking** stub instead of a future stub.

**Rationale**: booking-service is a synchronous Spring MVC service (plain
`@RestController` request/response, no WebFlux reactive chain to compose a future
against) — unlike api-gateway, which is WebFlux-based and needs a `FutureStub` to avoid
blocking its event loop. A blocking call inside booking-service's own request thread is
the correct, simplest match for its own threading model.

**merchant_id value**: `InitiateTransactionRequest.merchant_id` is populated from a new
configurable property, `booking.merchant-id` (default `HOTEL-001`), set via
`application.yml`/environment variable — the same externalized-config pattern already
used for every other cross-cutting setting in this codebase. No new merchant-onboarding
flow is introduced; this is a fixed identifier representing "the hotel" as a single
merchant.

**Alternatives considered**: FutureStub — rejected, forces booking-service's otherwise
synchronous saga into unnecessary async composition for no benefit at this layer's
scale.

**Call deadline**: every `InitiateTransaction` call uses
`stub.withDeadlineAfter(5, TimeUnit.SECONDS)`. On `DEADLINE_EXCEEDED` or `UNAVAILABLE`,
the booking — already persisted as HELD before the call was made (FR-006) — is simply
left HELD with no `transaction_id`; no retry is attempted at the call site. This matches
the spec's existing "payment system temporarily unreachable" edge case exactly: hold
expiry, not a retry loop at the gRPC call site, is the single mechanism that resolves a
booking whose payment call never returned in time. Rejected alternative: an
application-level retry-with-backoff around the gRPC call itself — rejected as a second,
redundant timeout-recovery mechanism layered on top of the one the spec already
specifies (hold expiry), and as scope creep for this layer (CLAUDE.md: "fix root causes,
not retries").

## 3. Kafka consumption of `transaction.status.updated`

**Decision**: A local copy of the `TransactionStatusEvent` envelope/payload DTO (same
shape as `notification-service`'s), consumed with a dedicated consumer group
(`booking-service`), idempotency enforced via a `processed_events` table with key
`booking:transaction.status.updated:{eventId}`, matching Principle III exactly.

**Rationale**: No shared DTO library exists in this codebase today — every consuming
service defines its own local copy of the envelope. Introducing a shared library now
would be a cross-cutting change well beyond this layer's scope (and explicitly against
CLAUDE.md's "no speculative features" / "fix root causes, not retries" framing, which is
about this layer's own correctness, not a refactor of unrelated services).

**Alternatives considered**: A new shared `events-common` module — rejected as
out-of-scope speculative infrastructure work for a single consumer.

## 3a. Race: `transaction.status.updated` arrives before the booking's `transaction_id` is committed

**Decision**: If the consumer finds no booking matching `payload.transactionId`, it does
**not** write a `processed_events` row — it throws, letting the Kafka listener
container's configured error handler (bounded retries with backoff, e.g. Spring Kafka's
`DefaultErrorHandler`/`ExponentialBackOff`) redeliver the record. Only after retries are
exhausted does it log at ERROR and let the record be skipped (or sent to a dead-letter
topic, per whatever the error handler is already configured to do) — it is never treated
as "this event has no booking and never will."

**Rationale**: booking-service commits the HELD booking row, calls `transaction-service`
over gRPC, and only then saves the returned `transaction_id` on that row — three
sequential steps, not one atomic unit with the event's production on the other side. A
`transaction.status.updated` event can therefore physically arrive and be consumed
before the `transaction_id` column commit lands, purely as a timing race, not a data
error. Marking that delivery "processed" (via a `processed_events` row) would permanently
and silently drop a real `AUTHORIZED`/`DECLINED`/`FAILED` outcome — a different, by-a-
hair worse version of exactly the "silently drop an AUTHORIZED outcome" failure FR-016
already forbids. Bounded retry-with-backoff is the correct response to a transient race;
only exhausting those retries (a genuinely anomalous case, e.g. a malformed event or a
transaction_id that truly belongs to no booking) escalates to an ERROR log.

**Alternatives considered**: write the `processed_events` row regardless and drop the
event — rejected, directly violates FR-016. Re-query on a short in-consumer sleep/retry
loop instead of relying on the Kafka error handler — rejected as reinventing
infrastructure the Kafka client already provides correctly and consistently with the
platform's other consumers.

## 4. FRONT_DESK role enforcement

**Decision**: Extend `api-gateway`'s existing `SecurityConfig.authorizeExchange(...)`
rule chain with `.pathMatchers("/api/v1/bookings/**",
"/api/v1/rooms/**").hasAnyRole("FRONT_DESK", "ADMIN")`, placed alongside the existing
transaction/reporting role rules.

**Rationale**: `api-gateway` already authenticates every request as a JWT resource
server and converts a JWT `authorities`/`scope` claim into Spring Security
`GrantedAuthority`s (`SecurityConfig.jwtAuthenticationConverter`). Role-gating a new path
prefix is a one-line addition to the same mechanism already used for
`/api/v1/transactions` (`MERCHANT`/`ADMIN`) and the sensitive reporting routes
(`ADMIN`/`SUPPORT`) — no new filter, converter, or auth mechanism is needed.
`ADMIN` is included alongside `FRONT_DESK` for operational access, consistent with how
every other role-gated route in this file also grants `ADMIN`.

**Alternatives considered**: A custom `GatewayFilterFactory` performing its own claim
check (the pattern used for the gRPC bridge) — rejected: that pattern exists because the
gRPC bridge needs custom request/response translation, not because of anything
role-check-specific; a plain `.hasAnyRole(...)` matcher is simpler and consistent with
every other role-gated route already in this file.

## 5. Hold expiry mechanism

**Decision**: A `@Scheduled(fixedDelay = ...)` job (`HoldExpiryService`) running on a
short interval (e.g. every 30s), selecting HELD bookings whose `created_at` is older
than a configurable `booking.hold-timeout-minutes` (default 10), and transitioning each
to CANCELLED with `cancellation_reason = HOLD_EXPIRED` in its own CockroachDB
transaction per booking (so one slow/failing row never blocks the rest of the batch).

**Rationale**: Every other scheduled/background concern in this codebase that isn't
event-driven uses Spring's own `@Scheduled`; introducing a separate job runner would be
unjustified new infrastructure for a single, lightweight periodic query.

**Alternatives considered**: A Kafka-delayed-message / dead-letter-based expiry —
rejected as unnecessary complexity for a simple age-based DB query that a `@Scheduled`
poll already answers correctly and cheaply at this scale.
