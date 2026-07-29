# Research: Authorization Service

**Phase**: 0 — Pre-implementation research
**Date**: 2026-06-11
**Feeds into**: plan.md, data-model.md, contracts/

---

## 1. Concurrent Credit-Limit Enforcement Without Race Conditions

**Decision**: Rely on CockroachDB's serializable isolation with Spring Retry (`@Retryable`)
to handle concurrent authorization conflicts, using a standard JPA read → validate → write
flow with `@Version` optimistic locking on `CardAccount`.

**Rationale**: CockroachDB runs at SERIALIZABLE isolation by default. When two concurrent
transactions both read and modify the same `card_accounts` row (e.g., two authorizations
for the same card token arriving within milliseconds), CockroachDB aborts one of them with
`SQLSTATE 40001` (serialization failure). Spring Retry (already in the tech stack from the
transaction-service) retries the aborted transaction transparently. On retry, the fresh
read reflects the committed credit deduction from the first transaction, so the second
authorization correctly evaluates against the reduced available credit.

This approach is preferred over:
- **Pessimistic locking (`SELECT FOR UPDATE`)**: Locks the row for the entire transaction
  duration, serializing all concurrent authorizations for the same card. Creates a hotspot
  and degrades throughput under concurrent card usage. CockroachDB's serializable isolation
  achieves the same safety without the locking overhead.
- **Atomic SQL update with WHERE clause** (`UPDATE SET available_credit -= amount WHERE
  available_credit >= amount`): Removes the ability to distinguish reason codes (INVALID_CARD
  vs CARD_NOT_ACTIVE vs INSUFFICIENT_CREDIT) in a single step. Requires a separate read
  first anyway to get card status; not simpler in practice.
- **JPA optimistic locking alone (without retry)**: `@Version` catches concurrent writes,
  but on a conflict the service must decide whether to retry or decline. Adding Spring Retry
  handles this transparently without custom retry logic.

**Alternatives considered**: See above.

**Key retry configuration**:
```
maxAttempts: 3
backoff: exponential, initial 50ms, multiplier 2, max 500ms
Retryable on: OptimisticLockingFailureException, CannotSerializeTransactionException
```

---

## 2. Issuer Validation via Internal Card Registry

**Decision**: The authorization-service owns and manages a `card_accounts` table in its
own CockroachDB schema. "Issuer validation" means looking up the card token in this table
to verify existence (`VALID_CARD`) and status (`ACTIVE`). No external card network call
is made in v1.

**Rationale**: The constitution (Principle I) establishes that card tokens are opaque Vault
ciphertext strings — the authorization-service cannot decrypt them to access the underlying
PAN or BIN (Bank Identification Number) that would be needed to route to an external
network. An internal card registry keyed on the opaque token is therefore both necessary
and sufficient for the initial version. A real-world production service would replace this
with an external network call, but that is explicitly out of scope per the spec's
assumptions.

**Card registry seeding**: For development and integration testing, `card_accounts` rows
are inserted via `@Sql` test annotations or a dedicated Flyway seed migration for the
`test` profile. No external API or admin interface for account management is built in v1.

---

## 3. Kafka Consumer Pattern: Manual Acknowledgement + Idempotency

**Decision**: Reuse the exact same pattern established by the transaction-service:
- Consumer group: `authorization-service-consumer`
- `AckMode.MANUAL_IMMEDIATE`
- `enable.auto.commit: false`
- `isolation.level: read_committed`
- Idempotency key: `auth:{eventType}:{eventId}` in a `processed_events` table
- `ON CONFLICT DO NOTHING` native INSERT returning rows_affected
- Acknowledge Kafka offset only after: idempotency record inserted + business state
  committed + result event published

**Rationale**: Identical reasoning to transaction-service (Principle III). Manual
acknowledgement ensures offsets are committed only after the full business effect is
durable. `read_committed` isolation prevents consuming events that were written by
transactions that later aborted. `ON CONFLICT DO NOTHING` makes the idempotency check
atomic within the same DB transaction as the credit deduction.

---

## 4. Outbound Kafka Event Schema Compatibility with transaction-service

**Decision**: Produce events to `transaction.status.updated` with:
- `eventType`: `"TransactionAuthorized"`, `"TransactionDeclined"`, or `"TransactionFailed"`
- `payload.transactionId`: UUID string (required — this is what transaction-service reads)
- Additional payload fields (`reasonCode`, `outcome`) for other potential consumers

**Rationale**: The transaction-service's `TransactionStatusListener` deserializes events
from `transaction.status.updated` into `StatusUpdateEvent` and routes on `eventType`.
It reads only `payload.transactionId` from the payload. The authorization-service MUST use
the exact event type strings `TransactionAuthorized`, `TransactionDeclined`, and
`TransactionFailed` to trigger the correct routing in the transaction-service consumer.
Adding `reasonCode` and `outcome` to the payload is safe (extra fields are ignored by
existing consumers) and provides useful context for future consumers such as the
notification-service and reporting-service.

---

## 5. Credit Deduction Atomicity with Authorization Record Persistence

**Decision**: Wrap the entire authorization operation (card lookup, validation, credit
deduction, authorization record save, idempotency record insert) in a single
`@Transactional` boundary. Publish the Kafka result event AFTER the transaction commits.

**Rationale**: If the Kafka publish happens inside the transaction, a Kafka broker
unavailability would cause the DB transaction to roll back, losing the authorization
decision. Publishing after commit means: if the Kafka publish fails, the DB state (credit
deducted, authorization record saved) is already durable; the Kafka consumer offset was not
acknowledged; the same event will be retried; the idempotency record will block
re-processing the business logic; and the service will attempt to re-publish the result
event. This gives at-least-once delivery of result events with exactly-once business effect.

**Exception**: The idempotency record IS inside the same DB transaction as the credit
deduction. The Kafka publish is outside (after `@Transactional` commit).

---

## 6. Structured Logging and PAN Masking

**Decision**: Reuse the `PanMaskingFilter` (custom Logback `TurboFilter`) and
`logback-spring.xml` pattern from the transaction-service.

**Rationale**: Zero raw card numbers in logs is a non-negotiable requirement (Principle I).
The `PanMaskingFilter` blocks any log message containing a 13–19 digit sequence. The
authorization-service uses the card token as an opaque key and never has access to the
raw PAN, but the filter provides a defense-in-depth safeguard. The same `logstash-logback-
encoder` produces structured JSON for the ELK stack, and Micrometer trace IDs are included
in every log entry via MDC.
