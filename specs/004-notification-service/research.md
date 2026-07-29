# Research: Notification Service

**Date**: 2026-06-12
**Feature**: Notification Service
**Spec**: specs/004-notification-service/spec.md

---

## Decision 1: Notification Channel Abstraction

**Decision**: Strategy pattern — a `NotificationChannelGateway` interface with `EmailGateway` and `SmsGateway` Spring `@Component` implementations, injected into `NotificationService` as `List<NotificationChannelGateway>`.

**Rationale**: Each gateway encapsulates one channel's send logic. Swapping providers requires only a new implementation. In tests, each gateway is mocked via `@Mock`, so no live email or SMS traffic occurs. The service iterates the injected list to send on all available channels — clean, extensible, no conditional branching on channel type in the service layer.

**Alternatives considered**:
- `@ConditionalOnProperty` to switch gateways: rejected — requires restart to change channel; harder to test both channels in the same test class.
- Single `NotificationDispatcher` with switch statement: rejected — violates open/closed; every new channel requires editing existing code.

---

## Decision 2: Transaction Boundary — Idempotency + Record Creation vs. Channel Send

**Decision**: `NotificationService.process()` is `@Transactional` and does: (1) insert idempotency key, (2) save `NotificationRecord`(s) with status `PENDING`. The listener then calls channel gateways outside the transaction and updates each record's status to `SENT` or `FAILED` in a subsequent non-transactional call.

**Rationale**: Channel sends are external network calls; they must not hold a DB transaction open. By saving the records inside the transaction and sending outside, the idempotency guarantee is preserved: if the application crashes between commit and channel send, records are orphaned as PENDING. On re-delivery, the idempotency key already exists, so the event is skipped. Per spec, retry of FAILED notifications is out of scope — PENDING orphans are acceptable.

**Alternatives considered**:
- Save record AFTER send: rejected — if the send succeeds but the DB write fails, the next re-delivery would send a duplicate notification.
- Per-(event, channel) idempotency keys: rejected — adds complexity; the single per-event key correctly deduplicates all channels together.

---

## Decision 3: Multi-Topic Kafka Consumer Configuration

**Decision**: Two separate `ConcurrentKafkaListenerContainerFactory` beans — `txStatusContainerFactory` (value type: `TransactionStatusEvent`) and `fraudAlertContainerFactory` (value type: `FraudAlertEvent`). Each `@KafkaListener` specifies its factory via `containerFactory`. Both share the same `notification-service-consumer` group ID.

**Rationale**: Each topic carries a different JSON schema. Using separate factories gives each deserializer the correct `spring.json.value.default.type` without ambiguity. Sharing a single group ID means CockroachDB partition assignment is balanced across both topic listeners within one consumer group.

**Critical**: `AUTO_OFFSET_RESET_CONFIG = "earliest"` must be set in Java code (not only YAML) for `@EmbeddedKafka` test reliability — consistent with the pattern established in authorization-service and fraud-detection-service.

**Alternatives considered**:
- Single factory with type-header routing: rejected — the upstream services explicitly disable type headers (`ADD_TYPE_INFO_HEADERS = false`); the receiver cannot use headers for deserialization.
- Raw `String` deserialization + manual Jackson parsing: rejected — adds boilerplate; the typed factory approach is idiomatic Spring Kafka.

---

## Decision 4: Masked Recipient in NotificationRecord

**Decision**: `NotificationRecord` stores a `recipientMasked` field containing a masked version of the actual recipient — `j***@example.com` for email (first character + *** + @domain) and `***-***-NNNN` for SMS (all but last 4 digits masked). The full recipient address is stored only in `CardholderContact`.

**Rationale**: Storing the full email or phone number in audit records would constitute PII in potentially long-lived log-queryable tables. The masked form is sufficient for debugging delivery issues without exposing PII. Consistent with Principle I and VI requirements for masking sensitive identifiers in logs and audit records.

**Alternatives considered**:
- Store full recipient in NotificationRecord: rejected — PII in audit records; conflicts with PCI-DSS minimisation principles.
- Store only cardToken reference: rejected — makes debugging specific delivery failures harder without an extra join.

---

## Decision 5: CardholderContact REST Management API

**Decision**: Expose a simple REST controller at `POST /api/v1/cardholder-contacts` and `PUT /api/v1/cardholder-contacts/{cardToken}` for upsert semantics. `GET /api/v1/cardholder-contacts/{cardToken}` for retrieval. No authentication required on this internal endpoint for Phase 1 — auth is handled at the api-gateway layer.

**Rationale**: US3 requires preference registration and update to be testable and callable. A REST endpoint is the simplest integration-testable surface for this data management story. The api-gateway will eventually route external preference management traffic here; for now the endpoint is internal-only.

**Alternatives considered**:
- Flyway seed data only (no API): rejected — makes US3 acceptance scenarios untestable without manual DB manipulation.
- gRPC endpoint: rejected — no gRPC stub or client exists for this service yet; the constitution requires the .proto to be designed and approved before implementation; REST is simpler for internal management CRUD.

---

## Decision 6: No Spring Retry

**Decision**: No Spring Retry dependency. The `processed_events` table uses native `ON CONFLICT DO NOTHING` for idempotency. No concurrent mutable row contention exists in the notification service (insert-only idempotency, insert-only notification records, simple upsert for contacts).

**Rationale**: Same rationale as fraud-detection-service. Retry is only needed when concurrent transactions compete to update the same row. The notification service has no such pattern: idempotency is pure insert, notification records are new inserts, contact upserts use SQL-level conflict resolution.

---

## Decision 7: Masked Card Number Ownership

**Decision**: `CardholderContact` stores the `maskedCardNumber` (e.g., `****-****-****-1234`). Upstream event payloads carry only the opaque `cardToken`. When a notification is dispatched, the service looks up `CardholderContact` by `cardToken` to retrieve both contact info and the masked card number for the notification body.

**Rationale**: Neither `AuthorizationResultEvent` nor `FraudAlertRaisedEvent` carries a masked card number — only an opaque token. The notification service owns its own contact registry and is the authoritative source for the display-safe card representation. This prevents PAN leakage even if upstream events were somehow misconfigured.

**Alternatives considered**:
- Request masked card number from transaction-service at dispatch time: rejected — synchronous cross-service call during Kafka consumer processing adds latency and a new failure point; Principle V prohibits REST service-to-service calls.
- Store masked card in the notification record: redundant — already in CardholderContact.

---

## Decision 8: Notification Content Templates

**Decision**: Notification content is assembled inline in `NotificationService` using simple string formatting — no external template engine. Templates are:
- Authorized: `"Payment approved: Card ending in {last4}. Reference: {transactionId}."`
- Declined: `"Payment declined: Card ending in {last4}. Reason: {reasonCode}. Reference: {transactionId}."`
- Fraud alert: `"URGENT: Suspicious activity on card ending in {last4}. Triggered rules: {rules}. Alert ID: {alertId}. Contact us immediately if this was not you."`

**Rationale**: Simple string formatting is sufficient at this stage. An external template engine (e.g., Thymeleaf, FreeMarker) adds complexity without adding value for the current scope. The last 4 digits are extracted from `CardholderContact.maskedCardNumber`.

---

## Decision 9: EventId Type Normalisation

**Decision**: Both event POJOs in the notification service (`TransactionStatusEvent`, `FraudAlertEvent`) use `String` for `eventId`. The `FraudAlertRaisedEvent` in fraud-detection-service uses `UUID`, but Jackson serializes `UUID` as a string in JSON. Defining `eventId` as `String` in our local POJO handles both without type errors.

**Rationale**: Avoids a UUID→String conversion step when building idempotency keys. Both POJOs use `String eventId`, ensuring uniform treatment across the two consumer paths. The idempotency key `notif:{eventType}:{eventId}` works identically for both event types.
