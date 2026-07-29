# Feature Specification: Notification Service

**Feature Branch**: `004-notification-service`

**Created**: 2026-06-12

**Status**: Draft

**Input**: User description: "Build the notification-service"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Transaction Status Notifications (Priority: P1)

When a payment is authorized or declined, the cardholder receives a real-time notification
informing them of the outcome. The notification includes the masked card identifier (last 4
digits only), the transaction amount, the currency, and the outcome reason for declines.
No raw card numbers, CVV, or full card identifiers appear in any notification content.

**Why this priority**: This is the most frequent notification path — every single completed
transaction produces either an authorization or a decline event. Cardholders expect
immediate confirmation of whether their payment was accepted. This is also the lowest-risk
story: the content is non-sensitive (masked card only) and the volume baseline establishes
the service's throughput.

**Independent Test**: Can be fully tested by publishing an `AuthorizationResult` event with
status `AUTHORIZED` for a card token that has registered contact preferences, and asserting
that exactly one notification record is persisted with the correct channel, recipient,
content, and status — and that a second delivery of the same event produces no additional
record.

**Acceptance Scenarios**:

1. **Given** an `AuthorizationResult` event with status `AUTHORIZED` arrives for a card
   token that has registered notification preferences, **When** the notification-service
   processes the event, **Then** the cardholder receives a notification stating the payment
   was approved, including the masked card number, the amount, and the currency — and a
   notification delivery record is persisted.

2. **Given** an `AuthorizationResult` event with status `DECLINED` arrives for a card token
   that has registered notification preferences, **When** the notification-service processes
   the event, **Then** the cardholder receives a notification stating the payment was
   declined along with the decline reason (e.g., insufficient credit, card not active), the
   masked card number, and the amount.

3. **Given** an `AuthorizationResult` event arrives for a card token with no registered
   contact preferences, **When** the notification-service processes the event, **Then** the
   event is acknowledged, no notification is sent, and a log entry is produced indicating
   no contact details were found for the token.

4. **Given** the same `AuthorizationResult` event is delivered twice (re-delivery),
   **When** the notification-service processes the duplicate, **Then** exactly one
   notification record exists — the duplicate is discarded without sending a second
   notification.

5. **Given** a notification channel (email or SMS) returns a delivery failure, **When**
   the notification-service attempts delivery, **Then** the notification record is persisted
   with status `FAILED`, the failure reason is recorded, and the event offset is still
   acknowledged.

---

### User Story 2 - Fraud Alert Notifications (Priority: P2)

When the fraud-detection service identifies suspicious activity on a card, the cardholder
receives an urgent alert immediately — regardless of their general notification preferences.
Fraud alerts are non-optional and always delivered through every available channel the
cardholder has registered (e.g., both email and SMS).

**Why this priority**: Fraud alert notifications are a regulatory and trust obligation.
Delayed or missed fraud alerts expose cardholders to financial harm and expose the platform
to compliance risk. They must always fire — preference opt-outs do not apply.

**Independent Test**: Can be fully tested by publishing a `FraudAlertRaised` event for a
card token that has registered contact preferences, and asserting that one notification
record per registered channel is persisted with type `FRAUD_ALERT` — and that a card
token with general transaction notifications disabled still receives the fraud alert.

**Acceptance Scenarios**:

1. **Given** a `FraudAlertRaised` event arrives for a card token with both email and SMS
   registered, **When** the notification-service processes the event, **Then** the
   cardholder receives an urgent fraud alert on both channels, each containing the masked
   card number, the suspicious transaction amount, and a contact instruction (e.g., "call
   us if not you").

2. **Given** a `FraudAlertRaised` event arrives for a card token that has opted out of
   transaction notifications, **When** the notification-service processes the event,
   **Then** the fraud alert is still delivered — preference opt-outs do not suppress
   fraud alerts.

3. **Given** a `FraudAlertRaised` event lists multiple triggered rule types (e.g.,
   velocity exceeded and amount anomaly), **When** the notification-service processes
   the event, **Then** the notification content summarises all triggered rules in a single
   alert — the cardholder does not receive one alert per rule.

4. **Given** the same `FraudAlertRaised` event is redelivered, **When** the
   notification-service processes the duplicate, **Then** exactly one notification record
   exists per channel — the duplicate is discarded without re-alerting.

---

### User Story 3 - Notification Preferences Management (Priority: P3)

Cardholders can register their contact details (email address, phone number) and configure
which types of transaction notifications they want to receive. Preferences are stored per
card token. Preferences can be updated at any time and changes take effect immediately for
subsequent events.

**Why this priority**: Without registered contact details, no notifications can be
delivered. The preferences store is the foundational data that US1 and US2 depend on.
It is listed P3 because in practice it is seeded or pre-populated; the service is
functional with seeded test data while the preference management story is developed.

**Independent Test**: Can be fully tested by registering contact preferences for a card
token, then confirming a notification event for that token produces a delivery attempt to
the registered address; then updating the preferences and confirming the next event uses
the updated contact details.

**Acceptance Scenarios**:

1. **Given** a card token with no existing preferences, **When** contact preferences are
   registered (email address and/or phone number, channels enabled/disabled), **Then** a
   preference record is persisted and subsequent events for that token use the new
   preferences immediately.

2. **Given** a card token with existing preferences, **When** the email address is updated,
   **Then** the next notification for that token is sent to the new address — not the old
   one.

3. **Given** a card token with transaction notifications disabled, **When** an
   `AuthorizationResult` event arrives, **Then** no transaction notification is sent —
   but fraud alerts for that card are still delivered (see US2).

4. **Given** a card token that has only SMS registered (no email), **When** an
   `AuthorizationResult` event arrives, **Then** only an SMS notification is dispatched.

---

### Edge Cases

- What happens when a notification event references a card token that has no registered
  contact preferences? The event is acknowledged and a warning log entry is produced;
  no notification is sent and no error is raised.
- What happens when a notification channel gateway is temporarily unavailable? The
  notification record is persisted with status `FAILED`; the Kafka offset is still
  acknowledged (the consumer does not retry blocking the partition).
- What happens when both email and SMS fail for a fraud alert? Both failures are recorded
  individually; the event is still acknowledged and both failure records are persisted.
- What happens when a malformed event (null transaction ID or null event ID) arrives?
  The event is logged as invalid, the offset is acknowledged, and no notification record
  is created.
- What happens when the same card token is registered twice? The second registration
  overwrites the first (upsert behaviour), not creates a duplicate.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The service MUST consume `AuthorizationResult` events from the event stream
  and dispatch a transaction status notification to the registered cardholder for the
  relevant card token.
- **FR-002**: The service MUST consume `FraudAlertRaised` events and dispatch an urgent
  fraud alert to the cardholder on all available registered channels, regardless of
  transaction notification preferences.
- **FR-003**: Transaction notifications (authorized/declined) MUST be suppressible per
  cardholder preference. Fraud alert notifications MUST NOT be suppressible.
- **FR-004**: All notification content MUST use the masked card identifier only (last 4
  digits). Raw card numbers, full card tokens, CVV, or any other sensitive card data
  MUST NOT appear in notification content, subject lines, or delivery metadata.
- **FR-005**: Every Kafka consumer MUST be idempotent — re-delivery of an event with the
  same event ID MUST produce exactly one notification record, never a duplicate delivery.
- **FR-006**: The service MUST persist a notification delivery record for every attempted
  delivery, capturing channel, recipient (masked), event type, status (SENT / FAILED), and
  timestamp.
- **FR-007**: Delivery failures from the notification channel MUST be recorded as FAILED
  status and MUST NOT cause the Kafka consumer to block or retry the offset indefinitely.
- **FR-008**: Cardholder notification preferences MUST be storable and retrievable by
  card token, including: email address, phone number, and per-channel enable/disable flags.
- **FR-009**: Preference updates MUST take effect immediately for all subsequent events —
  no restart or cache flush required.
- **FR-010**: Events with null or missing event ID or transaction ID MUST be discarded
  with a warning log; no notification record is created.
- **FR-011**: Fraud alerts triggered by multiple rule types MUST be consolidated into a
  single notification per channel — one alert per channel, not one per rule type.

### Key Entities

- **CardholderContact**: The registered contact information and notification preferences
  for a card token. Keyed by card token (opaque string). Contains email address (optional),
  phone number (optional), transaction notifications enabled flag, and registration
  timestamp.
- **NotificationRecord**: A persistent record of a single notification delivery attempt.
  Contains the event ID that triggered it, event type, channel (EMAIL / SMS), a masked
  recipient identifier, delivery status (SENT / FAILED), failure reason if applicable, and
  the timestamp.
- **ProcessedEvent**: Idempotency guard. Stores the event key (`notif:{eventType}:{eventId}`)
  and the time it was first processed. Consulted before any notification is dispatched.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of `AuthorizationResult` and `FraudAlertRaised` events for card tokens
  with registered contact details result in a notification delivery attempt within 5 seconds
  of the event being published to the event stream.
- **SC-002**: Duplicate event re-delivery never produces more than one notification record
  per event ID per channel — verified by delivering the same event 10 times and confirming
  exactly one record exists.
- **SC-003**: Fraud alerts are delivered even when the cardholder has disabled transaction
  notifications — 100% of fraud alert events bypass the preference opt-out gate.
- **SC-004**: No raw card number, full card token, CVV, or full expiry date appears in any
  notification content, subject line, log entry, or delivery metadata — 0 violations in
  automated security scan.
- **SC-005**: Delivery failures are recorded without blocking event consumption — the
  consumer continues processing subsequent events after a channel failure, with no partition
  lag accumulation.
- **SC-006**: All classes achieve 100% instruction and branch test coverage with no
  exclusions.

## Assumptions

- Card tokens are opaque strings produced by the transaction-service's tokenization vault;
  the notification-service never receives or stores raw card numbers.
- Masked card numbers (e.g., "****-****-****-1234") are carried in the upstream event
  payloads — the notification-service does not construct the mask itself.
- The notification-service does not perform actual email or SMS delivery in its own process;
  it integrates with an external notification gateway (e.g., SMTP relay, SMS provider) via
  a pluggable channel abstraction. Gateway credentials are supplied via environment
  configuration, not hardcoded.
- Notification channel gateways are external dependencies and are stubbed or mocked in
  tests — no live email or SMS is sent during automated test execution.
- Cardholder contact preferences are seeded or registered through a separate operational
  flow (e.g., card issuance). The notification-service provides the data store and
  management API; the full cardholder onboarding flow is out of scope.
- Retry of FAILED notifications is out of scope for this version — a failed delivery is
  recorded as FAILED and no automatic re-attempt is made.
- The service runs as a standalone Spring Boot microservice consuming from the same Kafka
  cluster as the other platform services.
- TDD with 100% instruction and branch coverage is mandatory per the project constitution.
- Idempotency key format follows the platform convention: `notif:{eventType}:{eventId}`.
