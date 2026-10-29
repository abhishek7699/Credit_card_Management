# Implementation Plan: Room Board

**Branch**: `009-room-board` | **Date**: 2026-10-29 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `specs/009-room-board/spec.md`

## Summary

Adds the room board (layer 2b) to the existing `booking-service` — no new service.
Front-desk staff check a `CONFIRMED` booking in (room `VACANT_CLEAN` → `OCCUPIED`,
booking → `CHECKED_IN`) and check it out (`CHECKED_IN` → `CHECKED_OUT`, room → `DIRTY`,
no payment of any kind). Front-desk or housekeeping staff update a room's status
through a fixed set of allowed transitions and view a board of every room grouped by
floor, ordered by position, each showing its status and — if occupied — the current
guest and dates. A room in `MAINTENANCE` is excluded from availability search
regardless of its booking calendar. Every multi-row transition (check-in, check-out,
status update) is concurrency-safe via a conditional `UPDATE ... WHERE id = ? AND
status = ?`, the same "push the race into one atomic statement" philosophy layer 2a
used for its double-booking guard — no new locking primitive. The room layout (18
rooms: 3 floors × 6) is re-seeded by a new, purely additive Flyway migration: existing
rooms keep their IDs, nothing is dropped. k8s/GKE wiring remains deferred (Roadmap);
this layer's delivery surface is docker-compose + CI + e2e step 14.

## Technical Context

**Language/Version**: Java 17 (unchanged)

**Primary Dependencies**: Same as layer 2a — Spring Boot 3.2.5 (Web, Data JPA,
Validation, Actuator), Flyway. This layer introduces no new external dependency: no
new Kafka consumer/producer, no new gRPC call — check-in, check-out, and room-status
updates are synchronous REST only, entirely local to `booking-service`'s own schema.

**Storage**: Same CockroachDB `booking_service` schema. One new, strictly additive
Flyway migration (`V5`) — adds columns and backfills/inserts rows; never drops a
column, a table, or changes an existing room's `id`.

**Testing**: JUnit 5 + Mockito (unit), Spring Boot Test + Testcontainers (CockroachDB)
for integration — this layer's integration tests don't need `@EmbeddedKafka` at all,
since nothing here is event-driven. 100% JaCoCo instruction + branch coverage
(Principle II, NON-NEGOTIABLE), same as every other layer.

**Target Platform**: Docker Compose only, same as layer 2a — no k8s/GKE manifests
(Roadmap backlog).

**Project Type**: Additive change to the existing `services/booking-service` Gradle
module; no new module, no new service.

**Performance Goals**: None beyond the platform norm — single-row/simple-join queries
over an 18-room table.

**Constraints**: Every multi-step state change (check-in, check-out, room-status
update) MUST be concurrency-safe via a single-transaction conditional `UPDATE`
(expected-current-status in the `WHERE` clause, checked by affected-row-count) — never
a bare `SELECT` followed by an unconditional `UPDATE`, and never a new explicit
row-lock primitive when a conditional update already gives the same guarantee
atomically. The room-layout migration MUST be additive only (FR carried from the
current step's explicit instruction). `MAINTENANCE` MUST exclude a room from
availability search independent of its booking calendar (FR-014).

**Scale/Scope**: 18 rooms (3 floors × 6), fixed by Flyway seed data — no layout editor,
no housekeeping task assignment, no maintenance ticketing, no payment at checkout
(layer 2c), no frontend (layer 2d), no real-time push.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-checked after Phase 1 design — see bottom.*

| Principle | Status | Notes |
|-----------|--------|-------|
| I. Tokenization & Zero Raw Card Data | N/A | This layer never touches payment or card data in any form — check-in/check-out/status updates are pure state transitions. |
| II. Test-First / 100% Coverage | PASS | Same TDD + 100% JaCoCo gate as every other layer. |
| III. Idempotent Event Consumers | N/A | No new Kafka consumer or producer is introduced — every operation in this layer is synchronous REST, local to `booking-service`'s own schema. |
| IV. Iterative One-Step Development | PASS | Phase-by-phase, with an explicit checkpoint after Setup + Foundational + User Story 1 (board) + User Story 2 (check-in), per the current step's instruction; no phase beyond that starts without a fresh go-ahead. |
| V. Layered Microservice Architecture & Communication Tiers | PASS | Extends the existing `booking-service` (already in Principle V's scope since the 2a amendment); no new communication tier — still Controller → Service → Repository, REST only, gateway-fronted. |
| VI. Observability & Audit Trail | PASS | Same structured-logging conventions; nothing here is a "financial operation" requiring the audit-log appender (Principle VI's stricter rule is about card-related state transitions specifically). |
| VII. Proto-First Service Contracts | N/A | No new or changed `.proto` contract. |
| VIII. Frontend Code Sharing | N/A | Layer 2d (frontend) is explicitly out of scope here. |

No violations requiring Complexity Tracking justification.

## Project Structure

### Documentation (this feature)

```text
specs/009-room-board/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md         # Phase 1 output
├── quickstart.md         # Phase 1 output
├── contracts/            # Phase 1 output
│   └── api.md
└── tasks.md              # Phase 2 output (/speckit-tasks — NOT created by this plan)
```

### Source Code (repository root)

```text
services/booking-service/src/main/resources/db/migration/
└── V5__add_room_layout_and_status.sql     # NEW — additive only (see Key Design Decisions #1)

services/booking-service/src/main/java/com/creditcard/booking/
├── domain/
│   ├── Room.java                   # MODIFIED: + floor, position, status (RoomStatus)
│   ├── RoomStatus.java              # NEW — VACANT_CLEAN, OCCUPIED, DIRTY, MAINTENANCE
│   └── BookingStatus.java          # MODIFIED: + CHECKED_IN, CHECKED_OUT
├── repository/
│   ├── RoomRepository.java         # MODIFIED: findAvailable excludes MAINTENANCE;
│   │                               # + findAllByOrderByFloorAscPositionAsc,
│   │                               # + updateStatusIfCurrent (conditional UPDATE)
│   └── BookingRepository.java      # MODIFIED: + updateStatusIfCurrent (conditional UPDATE),
│                                   # + findByStatus, + findByRoomIdAndStatus
├── service/
│   ├── RoomService.java            # NEW — status-transition validation + board assembly
│   └── BookingService.java         # MODIFIED: + checkIn(bookingId), + checkOut(bookingId)
├── controller/
│   ├── RoomController.java         # MODIFIED: + PATCH /api/v1/rooms/{id}/status,
│   │                               # + GET /api/v1/rooms/board
│   ├── BookingController.java      # MODIFIED: + POST /api/v1/bookings/{id}/check-in,
│   │                               # + POST /api/v1/bookings/{id}/check-out
│   └── dto/
│       ├── RoomStatusUpdateRequest.java   # NEW
│       ├── RoomBoardResponse.java          # NEW — list of floors, each a list of rooms
│       └── (BookingDetailResponse reused as-is for check-in/check-out responses)
└── exception/
    ├── InvalidBookingStateException.java        # NEW — check-in/check-out precondition failed
    └── InvalidRoomStatusTransitionException.java # NEW — PATCH /status rejected (409)

services/api-gateway/src/main/java/com/creditcard/gateway/config/
├── RouteConfig.java      # MODIFIED: 4 new routes (check-in, check-out, status PATCH, board),
│                         # all using the existing "booking-service-cb" breaker (5s is
│                         # correct here — none of these call gRPC)
└── SecurityConfig.java   # MODIFIED: two new, more specific matchers (board GET, status
                          # PATCH) adding HOUSEKEEPING, placed before the existing broad
                          # /api/v1/bookings/**+/api/v1/rooms/** FRONT_DESK/ADMIN rule —
                          # check-in/check-out fall through to that unchanged rule

docker-compose.yml        # No change needed — booking-service's entry already exists
.github/workflows/ci.yml  # No change needed — booking-service's health-wait already exists
scripts/e2e-test.sh       # New e2e step 14 (Phase 6, not this plan)
docs/BACKLOG.md           # New entry (Key Design Decision #7, added now per this step's
                          # explicit instruction, not deferred to Phase 6)
```

**Structure Decision**: Purely additive changes inside the existing `booking-service`
module and the existing `api-gateway` routes/security config. No new module, no new
service, no new top-level directory.

## Key Design Decisions

1. **Additive-only room-layout migration (`V5`).** Existing rows (14 rooms,
   `room_number` 101-106/201-206/301-302) map onto the 3-floor-×-6 layout exactly by
   their existing numbering (hundreds digit = floor, ones digit = position within the
   floor); 4 new rows (`303`-`306`) are inserted to complete floor 3. `floor`/`position`
   are added nullable, backfilled by `room_number`, then set `NOT NULL` — the standard
   safe sequence for adding a non-nullable column to a populated table. `status` is
   added `NOT NULL DEFAULT 'VACANT_CLEAN'` directly (a single default value needs no
   staged backfill). A `UNIQUE (floor, position)` constraint is added after backfill so
   the board can never have two rooms claiming the same slot. No `DROP`, no column
   removal, no existing `id` touched — satisfies the current step's explicit
   constraint.

2. **Concurrency safety via conditional `UPDATE`, not `SELECT ... FOR UPDATE`.**
   Every status transition in this layer (room status, booking check-in, booking
   check-out) is applied as `UPDATE <table> SET status = ? WHERE id = ? AND status =
   ?`, inspecting the affected-row count:
   ```java
   @Modifying
   @Query("UPDATE Room r SET r.status = :newStatus WHERE r.id = :id AND r.status = :expected")
   int updateStatusIfCurrent(@Param("id") UUID id,
                              @Param("newStatus") RoomStatus newStatus,
                              @Param("expected") RoomStatus expected);
   ```
   Zero rows affected means the precondition didn't hold *at the instant of the
   update* — whether because it never held, or because a concurrent request changed it
   first — and is treated identically (rejected, state unchanged). This is the same
   philosophy as layer 2a's unique-constraint double-booking guard: push the race into
   one atomic statement the database itself resolves, rather than a `SELECT` an
   application thread can act on stale information from.

3. **Check-in/check-out touch the room before the booking, always — and check-out's
   room update is unconditional.** Both operations update Room then Booking, in that
   order, every time — a fixed lock order across both operations rules out a
   cross-operation deadlock. Check-in's room update *is* conditional
   (`VACANT_CLEAN` → `OCCUPIED`, must affect exactly one row, or the whole operation
   aborts). Check-out's room update is **not** conditional — it unconditionally sets
   `DIRTY` once the booking's own `CHECKED_IN` → `CHECKED_OUT` conditional update
   succeeds, per spec.md's edge case ("booking state is authoritative for this
   transition"): check-out must never fail merely because the room's own status
   doesn't match some expectation.

4. **Room-status `PATCH` is validated in the service layer, enforced atomically in
   the same conditional update.** `RoomService` checks the requested `(current,
   target)` pair against the fixed allowed-transition set from FR-010 using a quick
   read, then performs the *same* conditional `UPDATE` as #2 using that `current` value
   as the expected status. If a concurrent request already changed the room's status
   between the read and the update, the conditional update's row count comes back 0 —
   surfacing as the same 409 a plain disallowed transition would, never a silently
   wrong write.

5. **Board query avoids N+1 with two queries, not one per room.** `RoomService.getBoard()`
   fetches all 18 rooms (`ORDER BY floor, position`) and all `CHECKED_IN` bookings in
   two queries, then joins them in memory by `roomId` (at most one `CHECKED_IN` booking
   per room can ever exist, by construction — a room can't reach `OCCUPIED` a second
   time without first passing back through `VACANT_CLEAN`). Grouping by floor is then a
   single pass over the already-ordered room list.

6. **Gateway RBAC: two new specific matchers, placed before the existing broad
   rule.** `GET /api/v1/rooms/board` and `PATCH /api/v1/rooms/{id}/status` each get
   their own `.pathMatchers(...)` entry granting `FRONT_DESK`, `HOUSEKEEPING`, and
   `ADMIN`, added **before** the existing `/api/v1/bookings/**` + `/api/v1/rooms/**`
   rule (Spring Security evaluates `authorizeExchange` matchers in declaration order —
   first match governs). Check-in and check-out are new paths under
   `/api/v1/bookings/**` that don't match either new, more specific rule, so they fall
   through unchanged to the existing `FRONT_DESK`/`ADMIN`-only rule — no new matcher
   needed for them at all.

7. **Backlog, logged now per this step's explicit instruction**: this layer does not
   check whether a room has any future `CONFIRMED` booking before allowing it to be set
   to `MAINTENANCE` — a room can be taken out of service while still holding
   reservations, with no warning, reassignment, or block. Added to `docs/BACKLOG.md`.

## Complexity Tracking

*No entries — Constitution Check reported no violations requiring justification.*

## Post-Design Constitution Re-Check

Re-checked after Phase 1 design (data-model.md, contracts/, quickstart.md): no new
violations. The conditional-update concurrency approach (#2-4) and the additive
migration (#1) stay entirely within `booking-service`'s own schema and introduce no
new communication tier, no new external dependency, and no change to Principle V's
service scope (already amended for `booking-service` in layer 2a). Gate status
unchanged from the pre-design check above.
