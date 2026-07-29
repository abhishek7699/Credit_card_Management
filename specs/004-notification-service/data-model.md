# Data Model: Notification Service

**Date**: 2026-06-12
**Feature**: Notification Service

---

## Entities

### 1. CardholderContact

Stores notification preferences and contact details for a card token. Keyed by the opaque
card token assigned by the transaction-service's tokenization vault.

**Table**: `cardholder_contacts`

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `card_token` | `VARCHAR(512)` | PRIMARY KEY | Opaque token from vault. Never a raw PAN. |
| `masked_card_number` | `VARCHAR(19)` | NOT NULL | Display-safe masked PAN (e.g., `****-****-****-1234`). |
| `email` | `VARCHAR(320)` | NULLABLE | Notification email address. |
| `phone_number` | `VARCHAR(20)` | NULLABLE | Notification phone number (E.164 format, e.g., +14155551234). |
| `transaction_notifications_enabled` | `BOOLEAN` | NOT NULL DEFAULT TRUE | Opt-in/out for AUTHORIZED/DECLINED/FAILED notifications. |
| `registered_at` | `TIMESTAMPTZ` | NOT NULL DEFAULT NOW() | When preferences were first registered. |
| `updated_at` | `TIMESTAMPTZ` | NOT NULL DEFAULT NOW() | Last preference update. |

**Constraints**:
- At least one of `email` or `phone_number` should be non-null for notifications to be deliverable (enforced at service layer, not DB level).
- `card_token` is immutable (PK). Only contact/preference fields can be updated.

**Indexes**:
- Primary key on `card_token` (sufficient — all lookups are by token).

---

### 2. NotificationRecord

Persistent audit of every notification delivery attempt. One record per attempted delivery
per channel per triggering event.

**Table**: `notification_records`

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `id` | `UUID` | PRIMARY KEY | Surrogate key generated at creation. |
| `event_id` | `VARCHAR(100)` | NOT NULL | The eventId from the triggering upstream event. |
| `event_type` | `VARCHAR(50)` | NOT NULL | E.g., `TransactionAuthorized`, `FraudAlertRaised`. |
| `channel` | `VARCHAR(10)` | NOT NULL | `EMAIL` or `SMS`. |
| `recipient_masked` | `VARCHAR(100)` | NOT NULL | Masked recipient: `j***@example.com` or `***-***-4567`. |
| `content` | `TEXT` | NOT NULL | Full notification body sent (no raw card data). |
| `status` | `VARCHAR(10)` | NOT NULL DEFAULT 'PENDING' | `PENDING`, `SENT`, or `FAILED`. |
| `failure_reason` | `TEXT` | NULLABLE | Error message from channel gateway if status=`FAILED`. |
| `created_at` | `TIMESTAMPTZ` | NOT NULL DEFAULT NOW() | When the record was created. |
| `sent_at` | `TIMESTAMPTZ` | NULLABLE | When delivery was confirmed by the channel gateway. |

**Indexes**:
- `idx_notif_records_event_id` on `(event_id)` — for deduplication queries and audit lookups.
- `idx_notif_records_status` on `(status)` — for monitoring PENDING/FAILED counts.

**State transitions**:
```
PENDING → SENT     (channel send confirmed)
PENDING → FAILED   (channel send returned error)
```
No transition from FAILED → SENT is permitted (no retry in this version).

---

### 3. ProcessedEvent

Idempotency guard. Prevents the same upstream event from being processed more than once.

**Table**: `processed_events`

| Column | Type | Constraints | Description |
|--------|------|-------------|-------------|
| `event_key` | `VARCHAR(255)` | PRIMARY KEY | Format: `notif:{eventType}:{eventId}` |
| `processed_at` | `TIMESTAMPTZ` | NOT NULL DEFAULT NOW() | When the event was first processed. |

**Key format examples**:
- `notif:TransactionAuthorized:a1b2c3d4-...`
- `notif:TransactionDeclined:e5f6g7h8-...`
- `notif:FraudAlertRaised:i9j0k1l2-...`

**Insert strategy**: Native SQL `INSERT INTO processed_events ... ON CONFLICT DO NOTHING` returning affected row count. `0` rows affected = duplicate event; `1` row affected = first delivery.

---

## Entity Relationships

```
CardholderContact (card_token PK)
    ↑ looked up at notification dispatch time (not a FK — avoids coupling)

NotificationRecord
    - references event_id (no FK to processed_events — different lifecycle)
    - is created in the same @Transactional as ProcessedEvent insertion
    - status updated after @Transactional completes

ProcessedEvent (event_key PK)
    - inserted atomically with NotificationRecord creation
```

No foreign keys between tables. The service enforces relationships in code. This keeps the
schema simple and avoids cross-table cascade issues.

---

## Flyway Migrations

| Migration | Table | Content |
|-----------|-------|---------|
| `V1__create_cardholder_contacts.sql` | `cardholder_contacts` | Table + index |
| `V2__create_notification_records.sql` | `notification_records` | Table + indexes |
| `V3__create_processed_events.sql` | `processed_events` | Table |

No seed data migrations — contacts are created via the REST management API or in tests.
