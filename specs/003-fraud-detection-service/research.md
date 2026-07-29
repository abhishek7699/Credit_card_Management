# Research: Fraud Detection Service

**Date**: 2026-06-12
**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md)

## Decision 1: Fraud Rule Architecture

**Decision**: Strategy pattern — one Spring `@Component` per rule implementing a `FraudRule`
interface; `FraudDetectionService` holds a `List<FraudRule>` injected by Spring.

**Rationale**: All rules must be evaluated independently (spec US1, scenario 5: multiple
rules can fire on the same transaction). A Chain of Responsibility would short-circuit after
the first match; Strategy evaluates all rules and collects every triggered name into a single
alert. Each rule is independently testable as a unit. New rules can be added without changing
existing rules or the service.

**Alternatives considered**:
- Chain of Responsibility — rejected: short-circuits on first match; multi-rule detection
  would require structural change.
- `switch` or `if-else` block in the service — rejected: not extensible; each rule addition
  requires editing the service; not independently testable.
- Rule engine (Drools) — rejected: unnecessary dependency for three well-defined rules;
  adds significant test complexity.

---

## Decision 2: Rule Configuration Storage Format

**Decision**: `fraud_rule_configs` table with `parameters` column as `TEXT` (JSON string).
Each rule parses its own parameters using Jackson `ObjectMapper` into a typed inner record.

**Rationale**: Different rules have different parameter sets (velocity uses count + window;
anomaly uses multiplier + lookback + ceiling; blacklist has no numeric params). A single JSONB
or TEXT column is the simplest representation that remains schema-flexible without a sparse
parameter table. Jackson is already on the classpath (Spring Boot Jackson autoconfiguration).
CockroachDB's TEXT type is sufficient — we do not need JSON path queries on parameters.

**Alternatives considered**:
- Separate typed columns per parameter (e.g., `max_transactions`, `window_minutes`) —
  rejected: sparse table with many NULLs; adding a new rule requires a schema migration.
- JSONB column type — considered; TEXT chosen because parameter values are only read
  wholesale, never queried by JSON path, making JSONB's overhead unnecessary.
- Separate parameter table per rule type — rejected: three tables for three rules adds
  complexity without benefit at this scale.

---

## Decision 3: Transaction History Table

**Decision**: Dedicated `transaction_records` table, one row per processed transaction
(written for every event — clean or fraudulent).

**Rationale**: Velocity and anomaly rules need a history of all transactions, not just
fraudulent ones. A clean transaction leaves no fraud_alert row, so querying fraud_alerts for
history would miss the majority of the signal. The spec states: "The card's transaction history
is derived from the fraud alert and transaction records already visible to this service."
Storing every processed transaction in `transaction_records` satisfies this directly.

**Alternatives considered**:
- Query `fraud_alerts` for history — rejected: clean transactions produce no alert; velocity
  count would be systematically under-counted.
- Query the upstream transaction-service via gRPC — rejected: creates a synchronous dependency
  on another service; violates fail-safe principle (FR-011); prohibited by constitution
  (each service owns its own schema).
- Combined fraud_alert + transaction_record in one table — rejected: conflates two distinct
  concerns (detection result vs. processing history); complicates queries.

---

## Decision 4: Fail-Safe vs. Fail-Closed on Rule Errors

**Decision**: Fail-safe (FR-011). Each rule is wrapped in `safeEvaluate()` which catches
`Exception`; on error the rule is treated as not-triggered, the error is logged as WARN,
and other rules continue evaluating.

**Rationale**: The spec explicitly requires fail-safe (FR-011): "inability to retrieve card
history → transaction is passed through, error logged." This is appropriate for a fraud
signal service: a DB hiccup should not cause a good transaction to be blocked, since
fraud-detection-service is asynchronous and does not make real-time authorization decisions.
False negatives from transient errors are preferred over false positives that could freeze
downstream review workflows.

**Alternatives considered**:
- Fail-closed (throw on rule error) — rejected: explicitly prohibited by FR-011; would cause
  all transactions to be treated as fraudulent during database connectivity issues.
- Partial fail-safe (only catch DB exceptions) — rejected: over-specific; any unexpected
  exception in a rule should be treated the same way for operational predictability.

---

## Decision 5: No Spring Retry Required

**Decision**: Spring Retry (`@Retryable`) is not added to this service.

**Rationale**: The authorization-service needed retry because it performed a
read-modify-write on `card_accounts.available_credit` (concurrent updates from multiple
events trigger SQLSTATE 40001). The fraud-detection-service has no such pattern:
- `fraud_alerts` — insert-only (new UUID each time)
- `transaction_records` — insert-only (deduplicated by `UNIQUE` constraint on `transaction_id`)
- `processed_events` — insert-only (`ON CONFLICT DO NOTHING`)
- `fraud_rule_configs` — read-only at evaluation time
- `blacklist_entries` — read-only at evaluation time

No shared mutable row is written concurrently. SQLSTATE 40001 cannot occur.

**Alternatives considered**:
- Add `@Retryable` as a precaution — rejected: adds `spring-retry` and `spring-aop`
  dependencies without a real use case; consistent with Principle IV (no speculative scaffolding).

---

## Decision 6: Kafka Consumer Configuration

**Decision**: `ConsumerConfig.AUTO_OFFSET_RESET_CONFIG = "earliest"` (explicit, not relying
on yaml defaults); `AckMode.MANUAL_IMMEDIATE`; `enable.auto.commit = false`;
`isolation.level = read_committed`.

**Rationale**: In integration tests using `@EmbeddedKafka`, the Kafka consumer partition
assignment may complete after a test message is published. With `latest` (Kafka's default),
the consumer repositions to after the message and never sees it, causing flaky Awaitility
timeouts. Setting `earliest` in the `KafkaConsumerConfig` Java class (not just in
`application.yml`) guarantees the consumer reads from the beginning of available messages,
making tests deterministic. This lesson was applied from debugging the authorization-service.

**Alternatives considered**:
- Rely on `application-test.yml` `auto-offset-reset: earliest` only — rejected: the
  `ConsumerFactory` bean is constructed from Java config (overrides yaml); the yaml value
  is ignored unless explicitly read in the Java config.

---

## Decision 7: Idempotency Key Schema

**Decision**: `fraud:TransactionInitiated:{eventId}` — consistent with the platform pattern
established in authorization-service (`auth:TransactionInitiated:{eventId}`).

**Rationale**: Service prefix (`fraud:`) scopes the key namespace per service, preventing
key collisions if multiple services share infrastructure or are compared in tooling. EventType
in the key allows future extension to other event types without key collisions.

---

## Decision 8: Kafka Publish Ordering (DB-first, then Kafka)

**Decision**: `FraudAlertRaisedEvent` is published to Kafka ONLY after the containing
`@Transactional` method commits. Offset is acknowledged only after the Kafka send completes.

**Rationale**: If Kafka publish occurs inside the transaction and the transaction later rolls
back, no event should have been published. By publishing after commit (in the listener, outside
the `@Transactional` boundary), the system guarantees: no event without a persisted alert.
If the Kafka send fails after DB commit, the event will not be published and the offset will
not be acknowledged. On redelivery, the idempotency check will skip re-writing the DB records
but the Kafka send will be retried — at-least-once delivery for the outbound event with
idempotent DB state. This is the same pattern as authorization-service.

---

## Decision 9: triggered_rules Encoding in FraudAlert

**Decision**: `triggered_rules` stored as a comma-delimited string (e.g.,
`"VELOCITY_EXCEEDED,AMOUNT_ANOMALY"`) in a TEXT column; deserialized in the Java domain object
as `List<String>` using a JPA `AttributeConverter`.

**Rationale**: CockroachDB TEXT is simple, portable, and queryable via `LIKE`. The list is
small (at most 3 entries). An array type or JSONB would work but adds unnecessary type-mapping
complexity for a field that is only ever read as a whole.

**Alternatives considered**:
- Store as JSONB array — considered; TEXT chosen for simplicity and compatibility.
- Use a separate `fraud_alert_rules` join table — rejected: over-normalized for at most 3
  entries; makes queries more complex; adds a sixth table.
