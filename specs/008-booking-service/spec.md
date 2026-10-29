# Feature Specification: Booking Service

**Feature Branch**: `008-booking-service`

**Created**: 2026-10-29

**Status**: Draft

**Input**: User description: "Build booking-service (Phase 2, MVP only). Java 17, Spring Boot 3.2.5, same conventions as existing services (own CockroachDB schema, Flyway, idempotent Kafka consumer with processed_events, event envelope, 100% JaCoCo). Features: Rooms seeded via Flyway (number, type, nightly rate), GET /api/v1/rooms?checkIn&checkOut returns available rooms. Reservations: POST /api/v1/bookings (roomId, dates, guest name, card details), GET /api/v1/bookings/{id}, DELETE /api/v1/bookings/{id} (cancel before confirm). Orchestrated saga: create reservation HELD (prevent double booking of same room/dates under concurrency) -> call transaction-service via existing gRPC proto (never store or log card data) -> store transactionId -> consume transaction.status.updated: AUTHORIZED -> CONFIRMED; DECLINED/FAILED -> CANCELLED + room released. Hold expiry: scheduled job cancels HELD reservations older than 10 minutes. api-gateway routes /api/v1/bookings/** and /api/v1/rooms/** with existing JWT + rate limit. No changes to payment services. Correlate by transactionId. Add to docker-compose, k8s base + overlays, CI, and e2e. Out of scope: housekeeping, pricing rules, folios, refunds, multi-property."

## Clarifications

### Session 2026-10-29

- Q: A payment outcome can arrive for a booking that is no longer HELD (it already expired or the guest already cancelled it). The original spec said to just "ignore" these — but an AUTHORIZED outcome means money actually moved, which can't be a silent no-op. How should each case be handled? → A: Never silently drop an AUTHORIZED outcome. If it arrives after hold expiry, confirm the booking if the room is still free for those dates; otherwise keep it cancelled, record a cancellation reason of `PAYMENT_AFTER_EXPIRY`, and log a warning for manual refund. If it arrives after the guest already cancelled, keep it cancelled, record a cancellation reason of `PAYMENT_AFTER_CANCEL`, and log a warning for manual refund. A DECLINED or FAILED outcome for a no-longer-HELD booking remains a true no-op. A `cancellation_reason` field is added to the booking to carry this.
- Q: Who is the actor that creates and manages bookings? → A: This is a B2B tool. The guest never calls the API directly — front-desk staff create and manage bookings on a guest's behalf. Booking and room-search endpoints require an authenticated caller with the `FRONT_DESK` role, enforced by the existing api-gateway JWT/RBAC mechanism.
- Q: Should a guest be able to cancel their own pending booking? → A: No. There is no self-service cancellation in this version — remove the cancel-booking capability (the DELETE endpoint, the guest-cancellation user story, and the corresponding functional requirement) entirely. The only ways a booking becomes CANCELLED are a declined/failed payment (FR-011) or hold expiry (FR-015). Since guest-initiated cancellation no longer exists, the `PAYMENT_AFTER_CANCEL` reason code and its handling rule are removed too — that case can no longer occur.
- Q: When an AUTHORIZED outcome arrives for a booking already cancelled by hold expiry, should the system ever reverse the cancellation and confirm it (if the room happens to still be free)? → A: No. That reverse transition is removed. An AUTHORIZED outcome after expiry always leaves the booking CANCELLED with reason `PAYMENT_AFTER_EXPIRY` and a logged warning for manual refund, regardless of whether the room is still free. CONFIRMED and CANCELLED are terminal states with no path back to HELD or to each other.
- Q: Does this layer's infrastructure wiring include Kubernetes (base manifests + kind/GKE overlays)? → A: No, not yet. This layer's wiring is limited to docker-compose, CI, and e2e steps 12-13. Kubernetes/GKE wiring for booking-service is deferred to the backlog (see the Roadmap in CLAUDE.md) and is out of scope for this spec and its plan.

---

## User Scenarios & Testing *(mandatory)*

### User Story 1 — Staff Books a Room and Reaches a Confirmed Reservation (Priority: P1)

A front-desk staff member, authenticated with the FRONT_DESK role, searches for rooms available over a date range on behalf of a guest, picks one, and submits a booking with the guest's name and payment details. The booking is held immediately so no one else can take the same room for those dates while payment is in progress, and once payment is authorized the booking becomes confirmed.

**Why this priority**: This is the entire reason the service exists — without a successful booking-to-confirmation path, there is no product. Every other story depends on this flow existing first.

**Independent Test**: Seed a room, call the search endpoint (as an authenticated FRONT_DESK caller) for a date range that includes it, submit a booking for it with a card that authorizes successfully, and confirm the booking's status becomes CONFIRMED and it carries the payment's transaction reference.

**Acceptance Scenarios**:

1. **Given** a room exists and is unbooked for a date range, **When** front-desk staff search for availability over that range, **Then** the room appears in the results.
2. **Given** an available room, **When** staff submit a booking for it on behalf of a guest with valid dates, the guest's name, and card details, **Then** the booking is created in a held state immediately and a booking identifier is returned.
3. **Given** a booking was just created and held, **When** the associated payment is authorized, **Then** the booking's status becomes confirmed.
4. **Given** a room with a confirmed or held booking for a date range, **When** a search is performed for an overlapping range, **Then** that room no longer appears in the results.
5. **Given** a booking identifier, **When** staff query its status, **Then** the current status (held, confirmed, or cancelled) and booking details are returned.
6. **Given** a caller that is not authenticated with the FRONT_DESK role, **When** they call any booking or room-search endpoint, **Then** the request is rejected.

---

### User Story 2 — Declined Payment Releases the Room (Priority: P2)

A guest's payment is declined or otherwise fails while their booking is held. The booking is cancelled automatically and the room becomes available again for other guests, with no manual intervention needed from front-desk staff.

**Why this priority**: Without this, a single declined card would permanently lock a room, making the service unsafe to run — but the core booking path (Story 1) can be demonstrated without it.

**Independent Test**: Create a booking with a card that is declined, and confirm the booking's status becomes CANCELLED and the room reappears in availability search results for the same date range.

**Acceptance Scenarios**:

1. **Given** a held booking awaiting payment, **When** the payment is declined, **Then** the booking's status becomes cancelled.
2. **Given** a held booking awaiting payment, **When** the payment fails for any other reason, **Then** the booking's status becomes cancelled.
3. **Given** a booking that was just cancelled due to a declined or failed payment, **When** availability is searched for the same room and dates, **Then** the room appears in the results again.

---

### User Story 3 — Abandoned Bookings Expire Automatically (Priority: P3)

Staff submit a booking and payment never completes (the payment system never returns an outcome, the attempt is otherwise abandoned, etc.). After a fixed window, the booking is cancelled automatically and the room is released, without anyone noticing or acting on it.

**Why this priority**: Protects room inventory from being silently held forever by abandoned attempts, but is a background safeguard rather than something staff directly trigger — the core flows work without it in a short demo.

**Independent Test**: Create a booking, artificially age it past the hold window, run the expiry process, and confirm its status becomes CANCELLED and the room is available again — without any payment outcome ever arriving for it.

**Acceptance Scenarios**:

1. **Given** a booking has been held longer than the hold window with no payment outcome received, **When** the expiry process runs, **Then** the booking's status becomes cancelled and the room becomes available again.
2. **Given** a booking is held but still within the hold window, **When** the expiry process runs, **Then** the booking is left untouched.
3. **Given** a booking already confirmed or cancelled, **When** the expiry process runs, **Then** it is not affected regardless of age.

---

### Edge Cases

- What happens when two staff submit a booking for the same room and overlapping dates at nearly the same instant? Exactly one booking must succeed in reaching a held state for that room/date combination; the other must be rejected immediately, before either reaches out for payment.
- What happens when a search or booking request specifies a check-out date on or before the check-in date? The request is rejected as invalid.
- What happens when a booking is submitted for a room that doesn't exist? The request is rejected.
- What happens if the payment outcome notification for a booking is delivered more than once (e.g., redelivery after a transient failure)? The booking's status must change at most once per outcome — a repeated notification has no further effect.
- What happens if an AUTHORIZED payment outcome arrives for a booking that was already cancelled by hold expiry? The booking stays cancelled — there is no reverse transition back to confirmed, even if the room happens to still be free. Its cancellation reason is set to `PAYMENT_AFTER_EXPIRY`, and a warning is logged for manual refund — this case is never silently dropped.
- What happens if a DECLINED or FAILED payment outcome arrives for a booking that is no longer held (already expired)? It is a true no-op — no money moved, so there is nothing to reconcile and no state change occurs.
- What happens if the system that authorizes payments is temporarily unreachable when a booking is created? The booking remains held (not immediately cancelled) so that hold expiry — not a transient outage — governs its fate, consistent with how the rest of the platform already treats payment-initiation failures.
- What happens if a caller without the FRONT_DESK role attempts to call a booking or room-search endpoint? The request is rejected by the existing api-gateway JWT/RBAC mechanism before it reaches booking-service.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST support defining an inventory of rooms, each with a room number, a room type, and a nightly rate.
- **FR-002**: System MUST allow searching for rooms available over a given check-in/check-out date range, returning only rooms with no held or confirmed booking overlapping that range.
- **FR-003**: System MUST reject a search or booking request where the check-out date is not strictly after the check-in date.
- **FR-004**: Front-desk staff MUST be able to submit a booking request on behalf of a guest for a specific room and date range, providing the guest's name and payment card details.
- **FR-005**: System MUST reject a booking request for a room that does not exist.
- **FR-006**: System MUST place a new booking into a held state immediately upon acceptance, before any payment outcome is known.
- **FR-007**: System MUST guarantee that at most one booking can be held or confirmed for the same room and overlapping dates at any time, even when booking requests for that room arrive concurrently.
- **FR-008**: System MUST NOT persist or write to any log the raw payment card details submitted with a booking.
- **FR-009**: System MUST initiate a payment authorization for each held booking and record the resulting payment transaction reference against that booking.
- **FR-010**: System MUST transition a held booking to a confirmed state when its payment is authorized.
- **FR-011**: System MUST transition a held booking to a cancelled state, releasing the room, when its payment is declined or fails for any other reason.
- **FR-012**: System MUST process a given payment outcome for a booking at most once, even if the outcome notification is delivered more than once.
- **FR-013**: Front-desk staff MUST be able to retrieve a booking's current status and details by its identifier.
- **FR-015**: System MUST automatically cancel and release any booking that remains in a held state longer than a configurable timeout, defaulting to 10 minutes.
- **FR-016**: System MUST NOT silently drop an AUTHORIZED payment outcome that arrives for a booking that is no longer held — it MUST always be evaluated and recorded per FR-019.
- **FR-017**: Booking and room-search operations MUST be reachable only through the existing api-gateway, which MUST enforce the same JWT authentication and rate-limiting rules already applied to the platform's other operations, plus a requirement that the authenticated caller holds the `FRONT_DESK` role.
- **FR-018**: System MUST NOT alter the behavior, data, or interfaces of the existing payment-processing services; this feature only consumes their existing capabilities.
- **FR-019**: When an AUTHORIZED outcome arrives for a booking already cancelled by hold expiry, System MUST keep the booking cancelled — never reversing the cancellation back to confirmed, regardless of whether the room is still free — record a cancellation reason of `PAYMENT_AFTER_EXPIRY`, and log a warning for manual refund follow-up.
- **FR-021**: When a DECLINED or FAILED outcome arrives for a booking that is no longer held, System MUST treat it as a no-op with no state change.
- **FR-022**: System MUST record a cancellation reason on every cancelled booking, identifying why it was cancelled.
- **FR-023**: CONFIRMED and CANCELLED MUST be terminal booking states — once a booking reaches either one, System MUST NOT transition it to any other state (no reverse transitions).

### Key Entities

- **Room**: A bookable unit of inventory. Has a room number, a room type, and a nightly rate. Availability for a date range is derived from the bookings against it, not stored as a standalone flag.
- **Booking**: A reservation of one room for a date range, created by front-desk staff on a guest's behalf. Has a guest name, check-in/check-out dates, a status (held, confirmed, or cancelled), and — once payment has been initiated — a reference to the associated payment transaction. Status transitions are one-directional: HELD → CONFIRMED or HELD → CANCELLED; CONFIRMED and CANCELLED are terminal, with no path back to HELD or to each other (FR-023). A cancelled booking also carries a cancellation reason recording why it was cancelled — notably `PAYMENT_AFTER_EXPIRY` for the late-authorization-after-expiry case in FR-019, distinguishing it from an ordinary declined payment. Payment card details are accepted as input but are never part of the stored or logged record.

---

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Front-desk staff can search availability and submit a booking in a single request/response cycle, with the booking's held status confirmed back to them immediately (no waiting for payment to find out the room was reserved).
- **SC-002**: When two booking requests for the same room and overlapping dates are submitted at the same time, exactly one succeeds and the other is rejected, 100% of the time, with no double-booked room ever observed.
- **SC-003**: A booking whose payment is declined sees the room become available to other guests again within a few seconds — no manual cleanup is ever required.
- **SC-004**: A booking abandoned before payment resolves is automatically released within the configured hold window (10 minutes) plus a small processing margin, every time, with no booking left permanently stuck.
- **SC-005**: No payment card detail is ever retrievable from a booking record, a log, or any downstream event.
- **SC-006**: A repeated or duplicate payment-outcome notification never changes a booking's final status more than the first delivery did.
- **SC-007**: Every late-arriving AUTHORIZED payment outcome for a no-longer-held booking results in a reason-coded cancelled booking (never a reverse transition to confirmed) plus a logged warning for manual refund follow-up — never a silent drop with no trace.
- **SC-008**: Every booking or room-search request from a caller without the FRONT_DESK role is rejected, 100% of the time.

---

## Assumptions

- **Actor model**: This is a B2B tool, not a guest-facing one. Front-desk staff authenticate with the platform's existing JWT/RBAC (role `FRONT_DESK`) and create/manage bookings on a guest's behalf; guests never call this service's API directly.
- **Guest identity**: A guest is identified by name only on the booking; no separate guest account or profile system is introduced in this version.
- **Single room per booking**: Each booking covers exactly one room for one contiguous date range. Multi-room or multi-property bookings are out of scope.
- **Pricing**: The nightly rate already defined on the room is used as-is; dynamic pricing, taxes, fees, and discounts are out of scope for this version.
- **Payment capability reuse**: This feature reuses the platform's existing payment-authorization capability as-is; no changes to how payments are processed elsewhere are introduced or required.
- **No refunds, no self-service cancellation**: There is no booking-cancellation endpoint in this version. A booking can only become CANCELLED automatically — via a declined/failed payment (FR-011) or hold expiry (FR-015) — and CANCELLED/CONFIRMED are terminal with no reverse transition (FR-023). The late-authorization-after-expiry case (FR-019) only logs a warning for a human to handle the refund manually; no automated refund flow is introduced.
- **No housekeeping or folio tracking**: Room cleaning/turnover status and itemized guest charges beyond the room rate are out of scope for this version.
- **Hold window default**: Absent a specified business rule, 10 minutes is used as the default time a booking may remain held awaiting payment before being automatically released.
- **Single property**: Room inventory belongs to a single property; multi-property support is out of scope for this version.
- **Deployment wiring scope**: This layer wires into docker-compose, CI, and e2e steps 12-13 only. Kubernetes (base manifests + kind/GKE overlays) is deferred to the backlog and is out of scope for this version.
