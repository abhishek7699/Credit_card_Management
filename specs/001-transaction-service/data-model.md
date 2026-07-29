# Data Model: Transaction Service

**Phase**: 1 — Design
**Date**: 2026-06-11
**Service**: `transaction-service`
**Database**: CockroachDB Serverless (PostgreSQL-compatible)

---

## Entities

### Transaction

Represents a single card payment attempt from creation (PENDING) through to a terminal
state (AUTHORIZED, DECLINED, or FAILED).

**JPA Entity**: `com.creditcard.transaction.domain.Transaction`
**Table**: `transactions`

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PRIMARY KEY, DEFAULT gen_random_uuid() | Transaction identifier returned to caller |
| `card_token` | VARCHAR(512) | NOT NULL | Vault Transit ciphertext (e.g., `vault:v1:8SDd3W...`); opaque; only Vault can decrypt |
| `masked_card_number` | VARCHAR(19) | NOT NULL | Last 4 digits only (e.g., `****-****-****-1234`); for display only |
| `cardholder_name` | VARCHAR(255) | NOT NULL | Cardholder name as submitted |
| `amount` | DECIMAL(15,2) | NOT NULL, CHECK (amount > 0) | Payment amount in major currency unit |
| `currency_code` | CHAR(3) | NOT NULL | ISO 4217 code (e.g., `USD`, `EUR`, `GBP`) |
| `status` | VARCHAR(20) | NOT NULL, DEFAULT 'PENDING' | One of: PENDING, AUTHORIZED, DECLINED, FAILED |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | Immutable; set on INSERT |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | Updated on every status change |
| `version` | BIGINT | NOT NULL, DEFAULT 0 | JPA optimistic locking (`@Version`) |

**Indexes**:
- PRIMARY KEY on `id`
- INDEX on `status` (for operational queries by status)

**Validation rules** (enforced at service layer before DB write):
- `amount` MUST be > 0
- `currency_code` MUST match `[A-Z]{3}`
- `status` transitions are one-way and enforced in code:
  - PENDING → AUTHORIZED, DECLINED, or FAILED (only allowed transition from PENDING)
  - AUTHORIZED, DECLINED, FAILED → no further transitions (terminal states)

**State machine**:
```
              ┌─────────────┐
              │   PENDING   │ ← initial state on creation
              └──────┬──────┘
                     │
         ┌───────────┼────────────┐
         ▼           ▼            ▼
   ┌──────────┐ ┌─────────┐ ┌────────┐
   │AUTHORIZED│ │DECLINED │ │ FAILED │
   └──────────┘ └─────────┘ └────────┘
   (terminal)   (terminal)  (terminal)
```

---

### ProcessedEvent

Records every inbound Kafka event that has been successfully processed, keyed by a unique
event identifier. This table is the foundation of the idempotency guarantee (Principle III).

**JPA Entity**: `com.creditcard.transaction.domain.ProcessedEvent`
**Table**: `processed_events`

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `event_key` | VARCHAR(255) | PRIMARY KEY | Composite key: `{prefix}:{eventType}:{eventId}` |
| `processed_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | When the event was first processed |

**Idempotency key schema**:
```
event_key = "tx:" + eventType + ":" + eventId

Examples:
  "tx:TransactionAuthorized:550e8400-e29b-41d4-a716-446655440000"
  "tx:TransactionDeclined:7c9e6679-7425-40de-944b-e07fc1f90ae7"
  "tx:TransactionFailed:3fa85f64-5717-4562-b3fc-2c963f66afa6"
```

**Atomicity guarantee**: The INSERT into `processed_events` and the UPDATE of `transactions.status`
MUST execute within the same CockroachDB transaction. If either fails, both are rolled back.
This is enforced by a single `@Transactional` method in `IdempotencyService`.

---

## Schema DDL

```sql
-- Run during service startup via Flyway (or manually in dev)

CREATE TABLE IF NOT EXISTS transactions (
    id             UUID         NOT NULL DEFAULT gen_random_uuid(),
    card_token     VARCHAR(512) NOT NULL,
    masked_card_number VARCHAR(19) NOT NULL,
    cardholder_name VARCHAR(255) NOT NULL,
    amount         DECIMAL(15,2) NOT NULL CHECK (amount > 0),
    currency_code  CHAR(3)      NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version        BIGINT       NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_transactions_status ON transactions (status);

CREATE TABLE IF NOT EXISTS processed_events (
    event_key    VARCHAR(255) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (event_key)
);
```

---

## Entity Relationships

```
transactions
  └─ (no FK relationships in this service)
     Each service owns its own schema; no cross-service table references.

processed_events
  └─ (no FK relationships; intentionally denormalized for atomic idempotency)
```

The `transaction-service` owns the `transactions` and `processed_events` tables exclusively.
No other service may read or write these tables directly (Principle V).

---

## JPA Entity Design Notes

- Use `@UuidGenerator` (Hibernate 6) for `id` to avoid sequence contention in CockroachDB.
- Use `@Version` on the `version` field for optimistic locking — prevents lost updates on
  concurrent status changes.
- Use `java.time.Instant` for all timestamp fields mapped to `TIMESTAMPTZ`.
- Map `status` to a `TransactionStatus` enum using `@Enumerated(EnumType.STRING)`.
- The `card_token` field is `@Column(length = 512)` to accommodate full Vault ciphertext strings.
- `masked_card_number` is `@Column(length = 19)` — format: `****-****-****-XXXX` (19 chars).
- `cardholder_name` must never be logged or included in event payloads beyond what is
  needed by downstream services (data minimization).
