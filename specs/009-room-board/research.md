# Phase 0 Research: Room Board

## 1. Additive room-layout migration

**Decision**: One new migration, `V5__add_room_layout_and_status.sql`:
`ALTER TABLE rooms ADD COLUMN floor INT` and `ADD COLUMN position INT` (both nullable
at first), backfill both for the 14 existing rows from their existing `room_number`
(101-106 → floor 1 positions 1-6; 201-206 → floor 2 positions 1-6; 301-302 → floor 3
positions 1-2), then `ALTER COLUMN ... SET NOT NULL` on both once backfilled.
`ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'VACANT_CLEAN'` needs no staged
backfill (a single default value applies to every existing row immediately). Insert 4
new rows (`303`-`306`, `SUITE`, floor 3 positions 3-6) to reach 18. Add
`UNIQUE (floor, position)` after backfill.

**Rationale**: The current step's instruction is explicit and non-negotiable: additive
only, never drop the table, never change an existing room's `id`. Nullable-then-backfill-
then-`NOT NULL` is the standard safe sequence for adding a required column to a
populated table (a direct `NOT NULL` add without a constant default would fail against
existing rows). Mapping the existing `room_number` scheme onto floor/position needs no
guessing — the numbering already encodes exactly this layout (hundreds digit = floor,
ones digit = position).

**Alternatives considered**: Re-seeding by deleting and re-inserting all 18 rows —
rejected outright, directly violates the "never change an existing room's `id`"
constraint (any existing booking's `room_id` foreign key would dangle or require a
cascading rewrite).

## 2. Concurrency safety for check-in, check-out, and room-status updates

**Decision**: A conditional `UPDATE ... WHERE id = ? AND status = ?` for every
transition, inspecting the affected-row count (`0` = precondition not met, reject;
`1` = succeeded). See plan.md's Key Design Decisions #2-4 for the exact shape and
per-operation specifics (check-in's room update is conditional; check-out's is not).

**Rationale**: This is the same correctness pattern layer 2a already established for
double-booking prevention (research.md §1 there: push the race into a single atomic
database statement rather than a `SELECT` an application thread could act on stale
information from). CockroachDB's SERIALIZABLE-only isolation means a naive
`SELECT`-then-`UPDATE` is exposed to serialization conflicts requiring an explicit
retry loop to be correct; a conditional `UPDATE`'s atomicity holds regardless of
isolation level and needs no retry logic at all — the single statement either affects
the row or it doesn't.

**Alternatives considered**: `SELECT ... FOR UPDATE` then a plain `UPDATE` — rejected,
reintroduces the same retry-on-serialization-conflict complexity the conditional-update
approach avoids, for no additional guarantee. Optimistic locking via a `@Version`
column — rejected as unnecessary: the conditional update's own `WHERE status = ?`
clause already *is* the version check, scoped to exactly the field that matters,
without adding a new column purely for that purpose.

## 3. Board assembly without N+1 queries

**Decision**: Two queries — all rooms ordered by `(floor, position)`, and all bookings
with `status = CHECKED_IN` — joined in memory by `roomId`.

**Rationale**: At most one `CHECKED_IN` booking can exist per room at any time (a room
must pass back through `VACANT_CLEAN` before it can be checked into again), so the
join is a simple one-to-zero-or-one map lookup, not a true relational join. Two queries
at this scale (18 rooms) is simpler to write and test than a single hand-rolled JPQL
left-join projecting mixed room/booking columns, for no measurable performance
difference at this scale.

**Alternatives considered**: A single JPQL `LEFT JOIN` query projecting both entities —
rejected as added query complexity with no benefit at 18 rows; reconsider only if the
layout ever grows enough for N+1-style per-room lookups to matter (it wouldn't, since
there's no per-room lookup here regardless — the "N+1" risk this avoids is calling a
separate "find the current booking for this room" query once per room while building
the response, which the two-query, in-memory-map approach avoids entirely).

## 4. Gateway RBAC for the new HOUSEKEEPING role

**Decision**: Two new, more specific `.pathMatchers(...)` entries (`GET
/api/v1/rooms/board`, `PATCH /api/v1/rooms/{id}/status`) granting `FRONT_DESK`,
`HOUSEKEEPING`, and `ADMIN`, placed *before* the existing broad
`/api/v1/bookings/**`+`/api/v1/rooms/**` rule in `SecurityConfig`'s
`authorizeExchange` chain.

**Rationale**: Spring Security evaluates `authorizeExchange` matchers in declaration
order and the first match governs a given request — exactly the same mechanism
already used to carve out stricter rules for specific reporting routes ahead of a
broader catch-all (`SecurityConfig.java`, existing reporting rules). No new filter,
converter, or authentication mechanism — `HOUSEKEEPING` is just another string the JWT
`authorities` claim can carry, handled by the existing converter.

**Alternatives considered**: A single combined matcher covering all of
`/api/v1/bookings/**`+`/api/v1/rooms/**` with `HOUSEKEEPING` added to its role list —
rejected, since it would also grant `HOUSEKEEPING` access to check-in/check-out and
booking-get/room-search, which the spec restricts to `FRONT_DESK` only (FR-015).
