# Feature Specification: Fraud Detection Service

**Feature Branch**: `003-fraud-detection-service`

**Created**: 2026-06-12

**Status**: Draft

**Input**: User description: "Build the fraud-detection-service: consumes TransactionInitiated events from Kafka, runs configurable fraud rules (velocity check, amount anomaly, blacklist check), persists fraud alerts to CockroachDB, publishes FraudAlertRaised event to Kafka when suspicious activity is detected. Clean transactions produce no event. Idempotent Kafka consumer. TDD with 100% coverage mandatory."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Suspicious Transaction Detection (Priority: P1)

A risk operations team needs every incoming payment transaction automatically screened against fraud rules in real time. When a transaction triggers one or more rules, a fraud alert is raised and downstream systems are notified immediately. Clean transactions require no action.

**Why this priority**: This is the core value of the service — without real-time detection and alerting, no fraud prevention occurs. All other stories depend on this foundation.

**Independent Test**: Can be fully tested by submitting a transaction that matches a known fraud rule and verifying that (1) an alert record is stored and (2) a FraudAlertRaised notification is produced. A clean transaction can be verified by confirming no alert is stored and no notification is produced.

**Acceptance Scenarios**:

1. **Given** a transaction arrives for a card that has exceeded the velocity threshold (too many transactions in a short window), **When** the service evaluates the transaction, **Then** a fraud alert is persisted with rule type "VELOCITY_EXCEEDED" and a FraudAlertRaised notification is produced.

2. **Given** a transaction arrives with an amount significantly higher than the card's recent spending pattern, **When** the service evaluates the transaction, **Then** a fraud alert is persisted with rule type "AMOUNT_ANOMALY" and a FraudAlertRaised notification is produced.

3. **Given** a transaction arrives for a card token that appears on the blacklist, **When** the service evaluates the transaction, **Then** a fraud alert is persisted with rule type "BLACKLISTED_CARD" and a FraudAlertRaised notification is produced.

4. **Given** a transaction arrives that satisfies no fraud rules, **When** the service evaluates the transaction, **Then** no alert is stored and no notification is produced.

5. **Given** a transaction matches multiple fraud rules simultaneously, **When** the service evaluates the transaction, **Then** a single fraud alert is persisted listing all triggered rule types, and one FraudAlertRaised notification is produced.

---

### User Story 2 - Configurable Fraud Rules (Priority: P2)

Risk managers need the ability to adjust fraud detection thresholds — such as the number of allowed transactions per time window, the amount anomaly multiplier, and which card tokens are blacklisted — without requiring a code change or system restart.

**Why this priority**: Fixed rules become stale as fraud patterns evolve. Configurability is essential for keeping detection effective, but the service still delivers core value with any reasonable default configuration.

**Independent Test**: Can be tested by changing a rule's threshold value in the configuration store and verifying that subsequent transactions are evaluated against the new threshold without redeploying the service.

**Acceptance Scenarios**:

1. **Given** the velocity rule is configured with a threshold of 5 transactions per 10 minutes, **When** a 6th transaction arrives for the same card within the window, **Then** a fraud alert is raised.

2. **Given** the velocity threshold is updated to 10 transactions per 10 minutes, **When** a 6th transaction arrives for the same card, **Then** no fraud alert is raised.

3. **Given** a card token is added to the blacklist, **When** a transaction for that token arrives, **Then** a fraud alert is raised.

4. **Given** a card token is removed from the blacklist, **When** a transaction for that token arrives and no other rule fires, **Then** no fraud alert is raised.

---

### User Story 3 - Exactly-Once Processing (Priority: P3)

Due to network conditions or broker retries, the same transaction event may be delivered more than once. The service must process each unique transaction exactly once, ensuring duplicate deliveries do not generate duplicate alerts or notifications.

**Why this priority**: Duplicate alerts would overload downstream fraud review workflows and degrade trust in the system. This is a correctness guarantee required before production use.

**Independent Test**: Can be tested by delivering the same transaction event 10 times and verifying that exactly one fraud alert is stored and at most one FraudAlertRaised notification is produced.

**Acceptance Scenarios**:

1. **Given** a suspicious transaction event has already been processed and an alert stored, **When** the identical event is received again, **Then** no second alert is stored and no second FraudAlertRaised notification is produced.

2. **Given** a clean transaction event has already been processed with no alert, **When** the identical event is received again, **Then** no alert is stored and no notification is produced.

3. **Given** 10 copies of the same suspicious event are delivered concurrently, **When** all copies are processed, **Then** exactly one alert record exists and at most one FraudAlertRaised notification is produced.

---

### Edge Cases

- What happens when the fraud rules configuration is empty or unavailable — does the service fail safe (treat all transactions as clean) or fail closed (reject processing)?
- How does the service handle a transaction event with a missing or malformed card token?
- What happens if persisting the fraud alert fails after the rules have already been evaluated?
- How are transactions handled when the card has no transaction history yet (velocity/anomaly baseline does not exist)?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The service MUST consume transaction events from the designated incoming event stream and evaluate each against all active fraud rules.
- **FR-002**: The service MUST persist a fraud alert record whenever one or more fraud rules are triggered for a transaction, recording the transaction identifier, card token, the set of triggered rule types, the transaction amount, currency, and the timestamp of detection.
- **FR-003**: The service MUST publish a FraudAlertRaised notification after successfully persisting a fraud alert; the notification MUST include the transaction identifier, card token, alert identifier, triggered rule types, and timestamp.
- **FR-004**: The service MUST NOT produce any output event for transactions that trigger no fraud rules.
- **FR-005**: The service MUST evaluate the velocity rule: if a card has exceeded the configured maximum number of transactions within the configured rolling time window, the rule is triggered.
- **FR-006**: The service MUST evaluate the amount anomaly rule: if a transaction amount exceeds the configured multiplier times the card's average transaction amount over a configurable lookback period, the rule is triggered. For cards with no transaction history, a configurable absolute amount ceiling is used as the baseline.
- **FR-007**: The service MUST evaluate the blacklist rule: if the card token appears in the active blacklist, the rule is triggered.
- **FR-008**: The service MUST process each unique transaction event exactly once; duplicate or redelivered events for the same transaction MUST be silently discarded after the first successful processing.
- **FR-009**: Fraud rule thresholds (velocity window, velocity count limit, anomaly multiplier, anomaly lookback period, anomaly ceiling, blacklist entries) MUST be configurable without redeploying the service.
- **FR-010**: The service MUST log a warning when it receives a malformed or incomplete transaction event and MUST discard the event without raising an alert or blocking subsequent processing.
- **FR-011**: The service MUST treat evaluation errors (e.g., inability to retrieve card history) as fail-safe: the transaction is passed through without raising an alert, and the error is logged for operational visibility.

### Key Entities

- **TransactionInitiatedEvent**: Inbound event representing a payment transaction. Key attributes: transaction identifier, card token, amount, currency code, event identifier, timestamp.
- **FraudAlert**: A persistent record of detected suspicious activity. Key attributes: alert identifier (unique), transaction identifier, card token, amount, currency, set of triggered rule names, detection timestamp.
- **FraudRule**: A configurable fraud detection rule. Key attributes: rule type (VELOCITY_EXCEEDED, AMOUNT_ANOMALY, BLACKLISTED_CARD), enabled flag, threshold parameters (varies by rule type), last-updated timestamp.
- **BlacklistEntry**: A card token flagged as prohibited. Key attributes: card token, reason, date added.
- **FraudAlertRaisedEvent**: Outbound notification produced when a fraud alert is persisted. Key attributes: alert identifier, transaction identifier, card token, triggered rule types, detection timestamp.
- **ProcessedEvent**: Idempotency record for a consumed event. Key attributes: event key (unique), processed timestamp.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Every transaction event is evaluated against all active fraud rules within 500 milliseconds of receipt under normal load.
- **SC-002**: The false-negative rate (suspicious transactions that pass undetected) is below 2% when rules are correctly configured against a representative transaction set.
- **SC-003**: Duplicate transaction events delivered up to 10 times produce exactly one fraud alert record and at most one outbound notification.
- **SC-004**: Fraud rule threshold changes take effect for new transactions within 60 seconds of being updated in the configuration store, without a service restart.
- **SC-005**: The service sustains processing of at least 500 transaction events per second without dropped messages or alert loss.
- **SC-006**: Test coverage for all fraud rule evaluation paths reaches 100%, verified by automated tooling on every build.
- **SC-007**: A malformed transaction event that cannot be parsed or lacks required fields does not cause the service to halt or produce incorrect alerts.

## Assumptions

- Each transaction event carries a unique event identifier that can be used as an idempotency key; this identifier is set by the upstream transaction service.
- Fraud rule configuration is stored in the same persistence layer as fraud alerts (CockroachDB), allowing the service to read updated thresholds at rule evaluation time.
- The card's transaction history used for velocity and anomaly calculations is derived from the fraud alert and transaction records already visible to this service, not from an external card management system.
- A single fraud alert is raised per transaction even when multiple rules fire; the alert records all triggered rule types.
- The blacklist is a flat list of card tokens; merchant-level or geographic blacklisting is out of scope for this version.
- The FraudAlertRaised event is published only after the alert is successfully persisted; if persistence fails, no event is published and the message offset is not committed.
- Metrics and operational dashboards are out of scope for this specification; the service exposes standard health endpoints.
- The service does not make real-time authorization decisions; it operates asynchronously and downstream systems act on the FraudAlertRaised notifications independently.
