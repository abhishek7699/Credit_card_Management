# Feature Specification: Room Board

**Feature Branch**: `009-room-board`

**Created**: 2026-10-29

**Status**: Draft

**Input**: User description: "Build layer 2b: Room board, in booking-service (no new service). Minimal, B2B staff only. Follow CLAUDE.md working approach. Room layout: each room has floor and position. Re-seed via Flyway as 3 floors x 6 rooms (layout is data, not an admin UI). Room status: VACANT_CLEAN, OCCUPIED, DIRTY, MAINTENANCE. Check-in: POST /api/v1/bookings/{id}/check-in (FRONT_DESK). Booking must be CONFIRMED, room must be VACANT_CLEAN -> booking CHECKED_IN, room OCCUPIED. Check-out: POST /api/v1/bookings/{id}/check-out (FRONT_DESK). Booking CHECKED_IN -> CHECKED_OUT, room DIRTY. No payment at checkout yet (that is layer 2c). Status updates: PATCH /api/v1/rooms/{id}/status (FRONT_DESK or HOUSEKEEPING). Allowed: DIRTY -> VACANT_CLEAN, VACANT_CLEAN/DIRTY -> MAINTENANCE, MAINTENANCE -> VACANT_CLEAN. Everything else rejected (409). Board: GET /api/v1/rooms/board (FRONT_DESK or HOUSEKEEPING) returns rooms grouped by floor, ordered by position, each with status and current guest name + dates if occupied. Availability search excludes MAINTENANCE rooms. Gateway routes + new HOUSEKEEPING role via existing RBAC. Wiring: docker-compose, CI, e2e step 14 (book -> confirm -> check-in -> board shows OCCUPIED -> check-out -> DIRTY -> housekeeping cleans -> VACANT_CLEAN). Out of scope: charges/folio/payment at checkout (2c), frontend (2d), housekeeping task assignment, maintenance tickets, layout editor, real-time push. Log extras in BACKLOG.md."

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Staff View the Room Board (Priority: P1)

A front-desk or housekeeping staff member opens the room board and sees every room in the property, grouped by floor and ordered by position within each floor, each showing its current status — and, for any occupied room, the current guest's name and stay dates.

**Why this priority**: This is the entire reason the layer exists — a single screen staff can check at any time to know the real-time state of the property. Every other story in this layer only matters because it changes what this view shows.

**Independent Test**: Seed the room layout, call the board endpoint, and confirm every seeded room appears exactly once, grouped by floor, ordered by position, each with a status (and no guest details on rooms that aren't occupied).

**Acceptance Scenarios**:

1. **Given** the seeded room layout, **When** staff request the board, **Then** every room appears, grouped by floor and ordered by position within each floor.
2. **Given** a room that is OCCUPIED by a checked-in booking, **When** staff request the board, **Then** that room's entry includes the guest's name and stay dates.
3. **Given** a room that is VACANT_CLEAN, DIRTY, or in MAINTENANCE, **When** staff request the board, **Then** that room's entry shows its status with no guest details.
4. **Given** a caller without the FRONT_DESK or HOUSEKEEPING role, **When** they request the board, **Then** the request is rejected.

---

### User Story 2 — Front Desk Checks a Guest In (Priority: P2)

A front-desk staff member checks a confirmed booking in when the guest arrives. The booking becomes checked-in and its room becomes occupied, immediately visible on the board.

**Why this priority**: This is the first physical event in a guest's stay and the trigger that makes the board show real state instead of an all-vacant layout — but the board (Story 1) already delivers value before any check-in happens.

**Independent Test**: Confirm a booking (via the existing booking saga), check it in, and confirm the booking's status becomes CHECKED_IN, its room's status becomes OCCUPIED, and the board reflects both.

**Acceptance Scenarios**:

1. **Given** a CONFIRMED booking for a room that is VACANT_CLEAN, **When** front-desk staff check it in, **Then** the booking's status becomes CHECKED_IN and the room's status becomes OCCUPIED.
2. **Given** a booking that is not CONFIRMED (e.g., still HELD, or CANCELLED), **When** front-desk staff attempt to check it in, **Then** the check-in is rejected and nothing changes.
3. **Given** a CONFIRMED booking whose room is not VACANT_CLEAN (OCCUPIED, DIRTY, or MAINTENANCE), **When** front-desk staff attempt to check it in, **Then** the check-in is rejected and nothing changes.
4. **Given** a caller without the FRONT_DESK role, **When** they attempt a check-in, **Then** the request is rejected.

---

### User Story 3 — Front Desk Checks a Guest Out (Priority: P3)

A front-desk staff member checks a guest out at the end of their stay. The booking becomes checked-out and its room becomes dirty, ready for housekeeping — with no payment or charge of any kind happening as part of this action.

**Why this priority**: Completes the stay lifecycle Story 2 started, but the board and check-in are both already useful without it existing yet in a short demo.

**Independent Test**: Check a booking in, then check it out, and confirm the booking's status becomes CHECKED_OUT, its room's status becomes DIRTY, and no payment-related call or record is produced.

**Acceptance Scenarios**:

1. **Given** a CHECKED_IN booking, **When** front-desk staff check it out, **Then** the booking's status becomes CHECKED_OUT and the room's status becomes DIRTY.
2. **Given** a booking that is not CHECKED_IN (e.g., still CONFIRMED, or already CHECKED_OUT), **When** front-desk staff attempt to check it out, **Then** the check-out is rejected and nothing changes.
3. **Given** a caller without the FRONT_DESK role, **When** they attempt a check-out, **Then** the request is rejected.

---

### User Story 4 — Housekeeping Updates Room Status (Priority: P4)

A housekeeping or front-desk staff member updates a room's status as it moves through cleaning or is taken in and out of service, so the board — and room availability — always reflect the room's real, current condition.

**Why this priority**: Closes the loop back to a bookable room, but Stories 1-3 already demonstrate the core lifecycle without it; this is what makes the lifecycle repeatable.

**Independent Test**: Take a DIRTY room and mark it VACANT_CLEAN (or a VACANT_CLEAN/DIRTY room into MAINTENANCE, or a MAINTENANCE room back to VACANT_CLEAN), and confirm the room's status changes, the board reflects it, and every other status change is rejected.

**Acceptance Scenarios**:

1. **Given** a DIRTY room, **When** staff mark it VACANT_CLEAN, **Then** the room's status becomes VACANT_CLEAN.
2. **Given** a VACANT_CLEAN or DIRTY room, **When** staff mark it MAINTENANCE, **Then** the room's status becomes MAINTENANCE.
3. **Given** a MAINTENANCE room, **When** staff mark it VACANT_CLEAN, **Then** the room's status becomes VACANT_CLEAN.
4. **Given** a room in any status, **When** staff attempt a status change not in the allowed list above (for example, an OCCUPIED room marked DIRTY, or a VACANT_CLEAN room marked OCCUPIED directly), **Then** the request is rejected and the room's status is unchanged.
5. **Given** a room in MAINTENANCE, **When** availability is searched for any date range, **Then** that room never appears in the results, regardless of whether it has any conflicting booking.
6. **Given** a caller without the FRONT_DESK or HOUSEKEEPING role, **When** they attempt a status update, **Then** the request is rejected.

---

### Edge Cases

- What happens when two check-in attempts for the same booking arrive at nearly the same instant? Exactly one succeeds in transitioning the booking and room; the other is rejected.
- What happens when check-in is attempted on a booking that doesn't exist? The request is rejected.
- What happens when check-out is attempted on a booking whose room, for whatever reason, is no longer OCCUPIED? The check-out still proceeds for the booking (booking state is authoritative for this transition) and the room is set to DIRTY.
- What happens when a status update is attempted on a room that doesn't exist? The request is rejected.
- What happens when a room is in MAINTENANCE and has no conflicting booking for a requested date range? It still does not appear in availability search results — MAINTENANCE excludes it independently of the booking calendar.
- What happens to a room's status when its occupying booking is still mid-stay and someone attempts to directly mark that room DIRTY or MAINTENANCE through the status-update endpoint? Rejected — OCCUPIED only ever transitions to DIRTY via check-out, never through the status-update endpoint.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST associate every room with a floor and a position, used to group and order rooms for display.
- **FR-002**: System MUST seed the room layout as 3 floors of 6 rooms each (18 rooms total), as fixed data — not through any administrative interface.
- **FR-003**: Every room MUST be seeded with an initial status of VACANT_CLEAN.
- **FR-004**: System MUST track each room's status as exactly one of: VACANT_CLEAN, OCCUPIED, DIRTY, or MAINTENANCE.
- **FR-005**: Front-desk staff MUST be able to check a booking in. The operation MUST succeed only when the booking's status is CONFIRMED and its room's status is VACANT_CLEAN, transitioning the booking to CHECKED_IN and the room to OCCUPIED.
- **FR-006**: System MUST reject a check-in attempt whenever the booking is not CONFIRMED or its room is not VACANT_CLEAN, leaving both unchanged.
- **FR-007**: Front-desk staff MUST be able to check a booking out. The operation MUST succeed only when the booking's status is CHECKED_IN, transitioning the booking to CHECKED_OUT and its room to DIRTY.
- **FR-008**: System MUST reject a check-out attempt whenever the booking is not CHECKED_IN, leaving both unchanged.
- **FR-009**: System MUST NOT process, initiate, or record any payment or charge as part of check-in or check-out.
- **FR-010**: Front-desk or housekeeping staff MUST be able to update a room's status, limited to exactly these transitions: DIRTY → VACANT_CLEAN, VACANT_CLEAN → MAINTENANCE, DIRTY → MAINTENANCE, and MAINTENANCE → VACANT_CLEAN.
- **FR-011**: System MUST reject any requested room-status transition not in the list in FR-010, leaving the room's status unchanged.
- **FR-012**: Front-desk or housekeeping staff MUST be able to view a room board listing every room grouped by floor and ordered by position within each floor, with each room's current status.
- **FR-013**: The room board MUST include the current guest's name and stay dates for every room whose status is OCCUPIED, and MUST NOT include guest details for any other room.
- **FR-014**: Room availability search MUST exclude any room whose status is MAINTENANCE, regardless of that room's booking calendar for the requested dates.
- **FR-015**: System MUST restrict check-in and check-out operations to callers authenticated with the FRONT_DESK role.
- **FR-016**: System MUST restrict room-status updates and room-board viewing to callers authenticated with the FRONT_DESK or HOUSEKEEPING role.
- **FR-017**: System MUST guarantee that at most one check-in (and, independently, at most one check-out) succeeds for a given booking even when requests for it arrive concurrently.

### Key Entities

- **Room** *(extends the existing entity from layer 2a)*: now additionally has a floor, a position (its order within that floor), and a status (VACANT_CLEAN, OCCUPIED, DIRTY, or MAINTENANCE) reflecting its real-world, physical condition — independent of its night-by-night booking calendar.
- **Booking** *(extends the existing entity from layer 2a)*: its status now additionally includes CHECKED_IN and CHECKED_OUT, reached only from CONFIRMED (via check-in) and CHECKED_IN (via check-out) respectively.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Staff can view the complete, correctly grouped and ordered room board in a single request, at any time, with no missing or duplicated rooms.
- **SC-002**: A guest's check-in is reflected in both the booking's status and the room board within the same request/response cycle — no polling or delay required.
- **SC-003**: A guest's check-out is reflected the same way, and the vacated room is marked DIRTY immediately, with no payment-related side effect of any kind.
- **SC-004**: A room placed into MAINTENANCE never appears in an availability search result, 100% of the time, until it is explicitly taken out of MAINTENANCE.
- **SC-005**: Every room-status transition outside the allowed set (FR-010) is rejected 100% of the time, with the room's prior status always preserved.
- **SC-006**: When two check-in (or two check-out) requests for the same booking arrive at the same time, exactly one succeeds and the booking never ends up in an inconsistent state.

## Assumptions

- **Layout is static data**: Floor and position are fixed, Flyway-seeded values for this layer; no API or UI exists to edit the layout, add rooms, or renumber floors.
- **Fixed property size**: 3 floors × 6 rooms (18 rooms total) replaces layer 2a's original flat 14-room seed for this layer.
- **Position is a display order, not a room number**: it orders rooms within a floor for the board; it is not the same as — and does not have to match — the room's existing room number.
- **HOUSEKEEPING is a new staff role**: authenticated through the same existing JWT/RBAC mechanism already used for FRONT_DESK; no new authentication flow, login screen, or credential type is introduced.
- **No real-time push**: the room board is a plain, synchronous read endpoint; staff (or a future frontend) poll it. No websocket, SSE, or other push mechanism is introduced.
- **No payment at checkout**: any balance, folio, or extra charges a guest may owe is out of scope for this layer (layer 2c); check-out here only changes booking and room status.
- **No housekeeping task assignment or maintenance ticketing**: a room-status update is a direct, unattributed state transition — there is no concept of "assigned to," a cleaning checklist, or a maintenance ticket/work order in this layer.
- **No layout editor**: adding, removing, or rearranging rooms, floors, or positions requires a future Flyway migration, not an API call.
- **Booking state and room state are independently authoritative for their own transitions**: check-out always succeeds for a CHECKED_IN booking regardless of what the room's own status happens to be at that instant (edge case above).
