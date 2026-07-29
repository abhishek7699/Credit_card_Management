# Data Model: Authorization Service

**Phase**: 1 — Design
**Date**: 2026-06-11
**Service**: `authorization-service`
**Database**: CockroachDB Serverless (PostgreSQL-compatible)
**Schema namespace**: `authorization` (or `defaultdb` with table-name prefixes in dev)

---

## Entities

### CardAccount

Represents a cardholder's account in the issuer registry maintained by this service.
The `card_token` is the opaque Vault ciphertext string received in `TransactionInitiated`
events — this service uses it as a lookup key and never decrypts it.

**JPA Entity**: `com.creditcard.authorization.domain.CardAccount`
**Table**: `card_accounts`

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PRIMARY KEY, DEFAULT gen_random_uuid() | Internal record ID |
| `card_token` | VARCHAR(512) | NOT NULL, UNIQUE | Opaque Vault ciphertext; used as lookup key; never decrypted by this service |
| `status` | VARCHAR(20) | NOT NULL, DEFAULT 'ACTIVE' | `ACTIVE`, `BLOCKED`, or `EXPIRED` |
| `credit_limit` | DECIMAL(15,4) | NOT NULL, CHECK > 0 | Maximum authorized spend allowed |
| `available_credit` | DECIMAL(15,4) | NOT NULL, CHECK >= 0 | Current spendable balance; reduced on AUTHORIZED; never below 0 |
| `currency_code` | CHAR(3) | NOT NULL | ISO 4217 code (e.g., `USD`, `EUR`, `GBP`) |
| `created_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | Immutable |
| `updated_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | Updated on every credit deduction |
| `version` | BIGINT | NOT NULL, DEFAULT 0 | JPA `@Version` optimistic locking — prevents concurrent over-spend |

**Indexes**:
- PRIMARY KEY on `id`
- UNIQUE INDEX on `card_token` (primary lookup path)

**Validation rules** (enforced at service layer and DB constraints):
- `available_credit` MUST never go below 0 (enforced by CHECK constraint + service logic)
- `available_credit` MUST never exceed `credit_limit`
- Status transitions (ACTIVE → BLOCKED, ACTIVE → EXPIRED) are admin-only and out of scope
  for v1; `status` is read-only from the authorization logic's perspective

**State machine**:
```
  ACTIVE ──────────────────────┐
    │ (admin: account blocked) │ (admin: card expired)
    ▼                          ▼
 BLOCKED                   EXPIRED
```
Only `ACTIVE` accounts pass the card validation check. Status changes are out of scope for
v1 (no admin API); status is set via Flyway seed migrations and test fixtures.

---

### AuthorizationRecord

Durable record of every authorization decision made by this service. Written atomically
with the idempotency record (same DB transaction). Never updated after initial insert.

**JPA Entity**: `com.creditcard.authorization.domain.AuthorizationRecord`
**Table**: `authorization_records`

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `id` | UUID | PRIMARY KEY, DEFAULT gen_random_uuid() | Record ID |
| `transaction_id` | UUID | NOT NULL | Originating transaction from transaction-service; NOT a foreign key (no cross-service DB links) |
| `card_token` | VARCHAR(512) | NOT NULL | Opaque token; same value as received in event |
| `requested_amount` | DECIMAL(15,4) | NOT NULL | Amount from the inbound event |
| `currency_code` | CHAR(3) | NOT NULL | Currency from the inbound event |
| `outcome` | VARCHAR(20) | NOT NULL | `AUTHORIZED`, `DECLINED`, or `FAILED` |
| `reason_code` | VARCHAR(50) | NULLABLE | `null` for AUTHORIZED; set for DECLINED/FAILED (see reason codes below) |
| `decided_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | When the decision was made |

**Indexes**:
- PRIMARY KEY on `id`
- INDEX on `transaction_id` (for audit queries by transaction)

**Reason codes** (stored in `reason_code`):

| Reason Code | Outcome | Meaning |
|-------------|---------|---------|
| `null` | AUTHORIZED | No issue; transaction approved |
| `INVALID_CARD` | DECLINED | Card token not found in the issuer registry |
| `CARD_NOT_ACTIVE` | DECLINED | Card status is BLOCKED or EXPIRED |
| `INSUFFICIENT_CREDIT` | DECLINED | `requested_amount > available_credit` |
| `INVALID_EVENT_PAYLOAD` | FAILED | Inbound event was malformed or missing required fields |
| `INTERNAL_ERROR` | FAILED | Unexpected system error during processing |

**Immutability**: `AuthorizationRecord` is written once and never updated. Any future
reversal or chargeback would be a separate record (out of scope for v1).

---

### ProcessedEvent

Idempotency guard. One row per successfully processed event. The `event_key` includes the
service prefix, event type, and event ID to guarantee uniqueness across services and event
types sharing the same topic.

**JPA Entity**: `com.creditcard.authorization.domain.ProcessedEvent` (or managed via
`ProcessedEventRepository` with a native query — same pattern as transaction-service)
**Table**: `processed_events`

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| `event_key` | VARCHAR(255) | PRIMARY KEY | `auth:{eventType}:{eventId}` |
| `processed_at` | TIMESTAMPTZ | NOT NULL, DEFAULT now() | When first processed |

**Idempotency key construction**:
```
auth:TransactionInitiated:550e8400-e29b-41d4-a716-446655440000
```
Prefix `auth:` disambiguates from the transaction-service prefix `tx:` in case both
services ever share a `processed_events` table (they do not; each service has its own).

**Atomicity**: Inserted in the same DB transaction as the `AuthorizationRecord` and the
`card_accounts` credit deduction. Uses `ON CONFLICT DO NOTHING` native INSERT; returns 0
rows if already present (duplicate event path), returns 1 if new.

---

## Flyway Migrations

All migrations are located at:
`services/authorization-service/src/main/resources/db/migration/`

| Version | Description |
|---------|-------------|
| `V1__create_card_accounts.sql` | Creates `card_accounts` table with all constraints |
| `V2__create_authorization_records.sql` | Creates `authorization_records` table and indexes |
| `V3__create_processed_events.sql` | Creates `processed_events` idempotency table |

Test-profile seed data (e.g., known card tokens with specific credit limits) is inserted
via `@Sql` annotations in integration test setup, not via a Flyway migration, to keep
production migrations clean.

---

## Entity Relationships

```
card_accounts (1) ──────────────── (many) authorization_records
  card_token (unique)                     card_token (non-FK reference)
                                          [no direct FK — cross-service data isolation]

processed_events
  event_key (PK) — standalone idempotency table; no FK to other tables
```

**Note**: `authorization_records.card_token` is NOT a foreign key to `card_accounts.card_token`
because:
1. A `FAILED` record can be created even when the card token is not in the registry
   (reason: `INVALID_CARD`); a FK would prevent this insert.
2. Card accounts could theoretically be archived; retaining the history requires no FK.
3. Cross-table consistency is enforced by application logic, not DB constraints.

---

## Sequence Diagram: Authorization Flow

```
TransactionInitiatedListener
        │
        ▼
IdempotencyService.recordAndExecute(key, runnable)
        │
        ├─ INSERT processed_events ON CONFLICT DO NOTHING
        │    ├─ 0 rows (duplicate) → return early, ack Kafka
        │    └─ 1 row (new event) → execute runnable
        │
        ▼
AuthorizationService.authorize(event)                @Transactional
        │
        ├─ cardAccountRepository.findByCardToken(token)
        │    ├─ null → outcome = DECLINED(INVALID_CARD)
        │    └─ found →
        │         ├─ status != ACTIVE → outcome = DECLINED(CARD_NOT_ACTIVE)
        │         └─ amount > available_credit → outcome = DECLINED(INSUFFICIENT_CREDIT)
        │
        ├─ If AUTHORIZED:
        │    cardAccount.availableCredit -= amount
        │    cardAccountRepository.save(cardAccount)     ← @Version check here
        │
        ├─ authorizationRecordRepository.save(record)   ← always (AUTHORIZED + DECLINED + FAILED)
        │
        └─ [transaction committed]
        │
        ▼
kafkaTemplate.send("transaction.status.updated", ...)  ← AFTER transaction commit
        │
        ▼
ack.acknowledge()                                       ← AFTER Kafka publish succeeds
```
