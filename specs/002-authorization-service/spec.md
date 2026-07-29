# Feature Specification: Authorization Service

**Feature Branch**: `002-authorization-service`

**Created**: 2026-06-11

**Status**: Draft

**Input**: User description: "Build the authorization-service: consumes TransactionInitiated events from Kafka, validates the card token against the issuer, checks credit limit, persists the authorization decision to CockroachDB, and publishes an AuthorizationResult event (AUTHORIZED or DECLINED) back to Kafka. Idempotent Kafka consumer. TDD with 100% coverage mandatory."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Card Validation and Credit Authorization (Priority: P1)

When a new payment is initiated, the authorization-service must decide whether to approve
or decline it. It first checks that the card is recognized and active (not blocked or
expired), then verifies the requested amount does not exceed the card's available credit.
If both checks pass, the transaction is authorized; if either fails, it is declined with a
specific reason. The decision is durably stored and published so the rest of the payment
pipeline can proceed.

**Why this priority**: This is the core function of the service. Every transaction must
receive an authorization outcome before the cardholder's payment can be confirmed or
rejected. Without this, all transactions remain permanently pending.

**Independent Test**: Can be fully tested end-to-end by publishing a `TransactionInitiated`
event for a known-active card with sufficient credit and asserting that exactly one
`AUTHORIZED` result event is published and one authorization record is persisted, with the
card's available credit reduced by the transaction amount.

**Acceptance Scenarios**:

1. **Given** a `TransactionInitiated` event arrives for a card token that is registered,
   active, and has available credit ≥ the requested amount, **When** the
   authorization-service processes the event, **Then** it persists an
   `AuthorizationRecord` with outcome `AUTHORIZED`, reduces the card's available credit by
   the transaction amount, and publishes an `AuthorizationResult` event with status
   `AUTHORIZED`.

2. **Given** a `TransactionInitiated` event arrives for a card token that is not found in
   the issuer registry, **When** the authorization-service processes the event, **Then** it
   persists an `AuthorizationRecord` with outcome `DECLINED` and reason `INVALID_CARD`, and
   publishes an `AuthorizationResult` event with status `DECLINED`.

3. **Given** a `TransactionInitiated` event arrives for a card token whose status is
   `BLOCKED` or `EXPIRED`, **When** the authorization-service processes the event, **Then**
   it persists an `AuthorizationRecord` with outcome `DECLINED` and reason
   `CARD_NOT_ACTIVE`, and publishes an `AuthorizationResult` event with status `DECLINED`.

4. **Given** a `TransactionInitiated` event arrives for an active, valid card token but the
   requested amount exceeds available credit, **When** the authorization-service processes
   the event, **Then** it persists an `AuthorizationRecord` with outcome `DECLINED` and
   reason `INSUFFICIENT_CREDIT`, and publishes an `AuthorizationResult` event with status
   `DECLINED`.

5. **Given** an internal system error occurs during processing (for example, the card
   registry is temporarily unavailable), **When** the authorization-service cannot complete
   either check, **Then** it publishes an `AuthorizationResult` event with status `FAILED`
   and does not commit the Kafka offset, allowing the event to be retried.

---

### User Story 2 - Idempotent Duplicate Event Handling (Priority: P2)

Kafka guarantees at-least-once delivery, meaning the same `TransactionInitiated` event may
arrive multiple times. The authorization-service must ensure that re-delivering any event
never produces more than one authorization decision, never double-charges or double-credits
available credit, and never publishes more than one `AuthorizationResult` event for the
same transaction.

**Why this priority**: Without idempotency, Kafka rebalances and consumer restarts could
cause duplicate authorizations — the most dangerous outcome in a payment system. This
constraint must be proven correct before the service can be considered production-ready.

**Independent Test**: Can be tested by publishing the same `TransactionInitiated` event
ten times in succession and asserting that exactly one `AuthorizationRecord` exists in the
database, exactly one `AuthorizationResult` event appears in the output topic, and the
card's available credit is reduced exactly once.

**Acceptance Scenarios**:

1. **Given** a `TransactionInitiated` event has already been fully processed (an
   authorization record persisted and a result event published), **When** the same event
   is delivered again, **Then** the service acknowledges the duplicate, produces no
   additional database records, and publishes no additional result events.

2. **Given** a `TransactionInitiated` event is delivered while the first processing
   attempt is still in progress (concurrent duplicate), **When** the second delivery
   completes, **Then** the system guarantees exactly one authorization outcome — no race
   condition can produce two competing records or two result events.

3. **Given** ten deliveries of the same event arrive in sequence, **When** all ten are
   processed, **Then** precisely one `AuthorizationRecord` and one `AuthorizationResult`
   event exist, and the card's available credit reflects exactly one deduction.

---

### User Story 3 - Authorization Record Auditability (Priority: P3)

Every authorization decision — whether approved or declined — must be durably persisted
with enough context to reconstruct what happened, when it happened, and why. This audit
trail is essential for dispute resolution, regulatory reporting, and downstream settlement.

**Why this priority**: Durable persistence of decisions is a compliance requirement.
It depends on P1 (the authorization flow) and P2 (idempotency) being stable first.

**Independent Test**: Can be tested by processing a mix of AUTHORIZED and DECLINED events
and asserting that each `AuthorizationRecord` contains the transaction ID, card token
(masked), amount, currency, outcome, reason code, and a timestamp — with no raw card
numbers present anywhere in the stored or logged data.

**Acceptance Scenarios**:

1. **Given** an authorization decision is reached, **When** the record is persisted,
   **Then** it contains: the originating transaction ID, a masked representation of the
   card token, the requested amount and currency, the outcome (`AUTHORIZED` or
   `DECLINED`), the reason code, and the decision timestamp.

2. **Given** an auditor queries the authorization records for a specific transaction ID,
   **When** the record is retrieved, **Then** no raw card number appears anywhere in the
   returned data — only masked identifiers.

3. **Given** an authorization decision is recorded, **When** a structured audit log entry
   is written, **Then** the entry contains the transaction ID, outcome, reason code, a
   masked card token, and an ISO-8601 timestamp — and zero raw card numbers.

---

### Edge Cases

- What happens when the `TransactionInitiated` event payload is malformed or missing
  required fields? → The event is acknowledged (preventing infinite redelivery) and an
  `AuthorizationResult` event with status `FAILED` and reason `INVALID_EVENT_PAYLOAD` is
  published.
- What happens when the card registry and the credit check both fail? → The first failing
  check wins; card validation (`INVALID_CARD` or `CARD_NOT_ACTIVE`) is evaluated before
  credit limit (`INSUFFICIENT_CREDIT`).
- What happens when the `AuthorizationResult` event cannot be published to Kafka? → The
  Kafka consumer offset is NOT committed; the event is retried on the next poll cycle.
- What happens if an authorization event arrives for a transaction that already has a
  terminal status in the transaction-service? → The idempotency guard prevents duplicate
  processing; the event is silently acknowledged.
- What happens if available credit is exactly equal to the requested amount? → The
  transaction is authorized (boundary is inclusive).
- What happens when the card registry is available but returns an inconsistent state
  during a concurrent update? → The service reads the committed state; no partial reads
  are permitted.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The system MUST consume `TransactionInitiated` events from the inbound
  payment event stream and produce a deterministic authorization outcome for each event.

- **FR-002**: The system MUST validate the card token against the issuer registry to
  confirm the card exists and its status is `ACTIVE`. Cards with status `BLOCKED`,
  `EXPIRED`, or unknown MUST be declined with the appropriate reason code.

- **FR-003**: The system MUST check the available credit on the card account. Any
  transaction whose requested amount exceeds the available credit MUST be declined with
  reason `INSUFFICIENT_CREDIT`.

- **FR-004**: Upon a successful authorization, the system MUST reduce the card account's
  available credit by the authorized amount as part of the same atomic operation that
  persists the authorization record.

- **FR-005**: The system MUST persist every authorization decision — whether AUTHORIZED
  or DECLINED — as a durable `AuthorizationRecord` before publishing the result event.

- **FR-006**: The system MUST publish an `AuthorizationResult` event (status: `AUTHORIZED`,
  `DECLINED`, or `FAILED`) to the outbound authorization result stream after persisting
  the decision. The event MUST carry the transaction ID, outcome, reason code, and a
  trace-propagation identifier.

- **FR-007**: Every authorization decision MUST be idempotent: re-delivering the same
  `TransactionInitiated` event MUST produce exactly the same observable outcome, with no
  duplicate records persisted and no duplicate result events published.

- **FR-008**: The idempotency record and the authorization business state MUST be committed
  atomically — either both succeed or neither does — so that a crash between the two cannot
  leave the system in a partially-committed state.

- **FR-009**: Kafka consumer offsets MUST be committed manually and only after the
  authorization result event has been successfully published and the idempotency record
  has been durably committed.

- **FR-010**: Every authorization decision MUST produce a structured audit log entry
  containing the transaction ID, masked card token, amount, currency, outcome, reason
  code, and an ISO-8601 timestamp. Raw card numbers MUST never appear in any log output.

- **FR-011**: When the card registry or credit store is unavailable, the system MUST
  publish an `AuthorizationResult` event with status `FAILED` rather than silently
  approving a transaction it cannot validate (fail-closed behavior).

### Key Entities

- **CardAccount**: Represents a cardholder's account in the issuer registry. Attributes:
  card token (opaque, used as a lookup key), account status (`ACTIVE`, `BLOCKED`,
  `EXPIRED`), credit limit, available credit, currency.

- **AuthorizationRecord**: The durable record of a single authorization decision.
  Attributes: record ID, originating transaction ID, card token (stored as opaque key),
  requested amount, currency, outcome (`AUTHORIZED` / `DECLINED` / `FAILED`), reason
  code, decided-at timestamp.

- **ProcessedEvent**: Idempotency record keyed by `{servicePrefix}:{eventType}:{eventId}`.
  Committed atomically with the `AuthorizationRecord` to prevent duplicate processing.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 95% of authorization decisions are completed — decision persisted and result
  event published — within 300 ms of receiving the `TransactionInitiated` event, measured
  end-to-end under normal operating load.

- **SC-002**: Duplicate delivery of any single event never produces more than one
  `AuthorizationRecord` or more than one `AuthorizationResult` event; verified by
  delivering the same event 10 consecutive times and asserting exactly one record and one
  result event exist.

- **SC-003**: When a card's available credit is reduced by an authorized transaction, the
  reduction is exact and atomic: no concurrent authorization can over-spend the credit
  limit, and no partial credit reductions occur on declined transactions.

- **SC-004**: 100% of authorization decisions appear in the structured audit log with a
  masked card token, reason code, and timestamp; zero raw card numbers appear in any log
  output across all test and production runs.

- **SC-005**: When the card registry or credit store is unavailable, 100% of in-flight
  transactions receive a `FAILED` result event within the same processing cycle — no
  transaction is silently approved without validation.

- **SC-006**: The service sustains at least 500 authorization decisions per second without
  exceeding the SC-001 latency threshold, measured under sustained load.

- **SC-007**: All service code achieves 100% line and branch test coverage, enforced as a
  CI/CD build gate before any merge is permitted.

## Assumptions

- Card tokens in `TransactionInitiated` events are opaque Vault ciphertext strings
  (format `vault:v1:<ciphertext>`); the authorization-service uses the token as an opaque
  string key to look up `CardAccount` records and never decrypts it to access the raw PAN.
- The authorization-service owns and manages its own `CardAccount` data. In the initial
  version, accounts are seeded via database migration scripts (test fixtures for
  development). A card-management service or admin API for account provisioning is out of
  scope for this iteration.
- The authorization-service has its own CockroachDB schema (separate from the
  transaction-service); no cross-service table access is permitted.
- The `AuthorizationResult` event is published to the `transaction.status.updated` Kafka
  topic, which the transaction-service already consumes to update transaction status.
- Available credit is tracked in the `CardAccount` record within the authorization-service's
  own database; settlement (restoring credit after payment) is handled by a separate
  settlement-service and is out of scope for this iteration.
- No synchronous gRPC server endpoint is exposed in the initial version; all communication
  is through Kafka. A query interface for authorization history may be added in a later
  iteration.
- The service does not communicate with external card networks (Visa, Mastercard) in the
  initial version; all validation is performed against internally managed account data.
- Authorization decisions for DECLINED transactions do not modify available credit — only
  AUTHORIZED decisions reduce it.
- The idempotency key schema follows the established platform convention:
  `{servicePrefix}:{eventType}:{eventId}` stored in a `processed_events` table.
- "Issuer validation" in this context means checking the `CardAccount` registry maintained
  by this service — not calling an external network or third-party issuer API.
