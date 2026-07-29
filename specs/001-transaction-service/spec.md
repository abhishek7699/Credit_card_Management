# Feature Specification: Transaction Service

**Feature Branch**: `001-transaction-service`

**Created**: 2026-06-11

**Status**: Draft (Amended 2026-06-11 — Vault tokenization)

**Input**: User description: "Build the transaction-service: the core service that accepts incoming card payment requests via the api-gateway, immediately tokenizes the raw card number, persists the transaction record with status PENDING, publishes a TransactionInitiated event for downstream services, and returns a transactionId to the caller. Handles status updates from downstream services via events (AUTHORIZED, DECLINED, FAILED). All event consumers must be idempotent. TDD with 100% coverage is mandatory."

**Amendment 2026-06-11**: Tokenization is delegated to an external encryption vault service. The vault encrypts the card number at rest and returns an opaque token. The transaction service stores the token and a masked card number only. Raw card numbers never enter our systems.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Card Payment Submission (Priority: P1)

A payment system (acting through the gateway) submits a new card payment request containing card details, an amount, and a currency. The transaction service delegates card number tokenization to an external encryption vault service: the vault encrypts the card number at rest and returns an opaque token. The transaction service stores only the token and a masked representation of the card number (last 4 digits). No raw card data touches our storage at any point. A new transaction record is created in PENDING status, downstream services are notified asynchronously, and the caller receives a unique transaction identifier.

**Why this priority**: This is the foundational flow — no other story is possible without a transaction being created. It also enforces the project's two most critical security rules: tokenization at the point of entry via an external vault, and zero raw card data in our systems.

**Independent Test**: Can be fully tested by submitting a well-formed payment request and verifying: (a) a transaction identifier is returned, (b) the persisted record contains a card token and a masked card number — never a raw card number — with PENDING status, (c) a downstream notification was emitted.

**Acceptance Scenarios**:

1. **Given** a valid card payment request with card details, amount, and currency, **When** the request is submitted, **Then** a unique transaction identifier is returned and the transaction is persisted with PENDING status, a card token, and a masked card number (e.g., ****-****-****-1234) — the raw card number is not stored.
2. **Given** a valid card payment request, **When** the request is submitted, **Then** a "new transaction" notification is dispatched to downstream services before the response is returned.
3. **Given** a valid card payment request, **When** the transaction record is created, **Then** no raw card number, CVV, or card expiry date appears anywhere in our stored records, logs, or outbound notifications.
4. **Given** a card payment request with an invalid or missing required field, **When** the request is submitted, **Then** the submission is rejected with a descriptive error and no transaction record is created.
5. **Given** the external tokenization vault service is unavailable, **When** a card payment request is submitted, **Then** the submission is rejected with an error, no transaction record is created, and no partial or incomplete record is left in storage.

---

### User Story 2 - Transaction Authorized (Priority: P2)

After the authorization service has assessed the payment, it reports the outcome back. The transaction service receives the authorization result (approved or declined by the card network) and updates the transaction status to AUTHORIZED or DECLINED accordingly. The updated status is then available for retrieval.

**Why this priority**: Authorization is the most common terminal outcome. Operators and the caller need to know whether a payment was approved.

**Independent Test**: Can be fully tested by creating a PENDING transaction, sending an authorization result notification, and verifying the transaction status is updated to AUTHORIZED or DECLINED.

**Acceptance Scenarios**:

1. **Given** a transaction in PENDING status, **When** an authorization-approved notification is received for that transaction, **Then** the transaction status is updated to AUTHORIZED.
2. **Given** a transaction in PENDING status, **When** an authorization-declined notification is received for that transaction, **Then** the transaction status is updated to DECLINED.
3. **Given** a transaction already in AUTHORIZED status, **When** another authorization notification arrives for the same transaction, **Then** the status remains AUTHORIZED and no duplicate state change occurs.
4. **Given** a transaction already in DECLINED status, **When** another authorization notification arrives, **Then** the status remains DECLINED and no duplicate state change occurs.

---

### User Story 3 - Transaction Failed (Priority: P3)

If downstream processing encounters an unrecoverable system error, the transaction service receives a failure notification and updates the transaction status to FAILED, distinguishing a system error from a deliberate decline.

**Why this priority**: Distinguishing FAILED (system error) from DECLINED (card network decision) is essential for operational monitoring, retry eligibility, and customer communication.

**Independent Test**: Can be fully tested by creating a PENDING transaction, sending a failure notification, and verifying the transaction status is updated to FAILED.

**Acceptance Scenarios**:

1. **Given** a transaction in PENDING status, **When** a failure notification is received, **Then** the transaction status is updated to FAILED.
2. **Given** a transaction in AUTHORIZED or DECLINED status, **When** a failure notification arrives, **Then** the status remains unchanged (terminal state is protected).

---

### User Story 4 - Idempotent Duplicate Notification Handling (Priority: P2)

In a distributed event-driven system, the same notification may be delivered more than once (network retries, consumer restarts). The transaction service processes each unique notification exactly once, regardless of how many times it is delivered.

**Why this priority**: Without idempotency, duplicate deliveries cause double state changes, corrupting transaction history. This is as critical as the happy path for correctness.

**Independent Test**: Can be fully tested by delivering the same status-update notification twice and verifying: (a) the transaction status reflects the first delivery, and (b) no duplicate history records or error responses occur on the second delivery.

**Acceptance Scenarios**:

1. **Given** a PENDING transaction, **When** the same AUTHORIZED notification is delivered twice, **Then** the transaction status is AUTHORIZED exactly once and the second delivery is silently acknowledged without side effects.
2. **Given** a PENDING transaction, **When** the same FAILED notification is delivered ten times, **Then** the transaction status is FAILED exactly once.
3. **Given** a new payment request submitted twice with identical payload, **When** both requests arrive concurrently, **Then** only one transaction record is created and both callers receive the same transaction identifier.

---

### Edge Cases

- What happens when the external tokenization vault service is unavailable or returns an error during a payment submission — is the submission rejected entirely, or retried?
- What happens when the tokenization vault accepts the request but times out before returning the token?
- What happens when a status update notification references a transaction identifier that does not exist?
- What happens when two conflicting status updates (e.g., AUTHORIZED and DECLINED) arrive simultaneously for the same transaction?
- What happens when downstream notification dispatch fails after the transaction record has been persisted?
- What happens when a card payment request is submitted with an amount of zero or a negative value?
- What happens when a very large number of payment requests arrive concurrently — are transaction identifiers guaranteed unique?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST accept a card payment request containing: card number, card expiry, CVV, cardholder name, payment amount (positive non-zero decimal), and currency code.
- **FR-002**: System MUST delegate card number tokenization to an external encryption vault service. The system submits the raw card number to the vault; the vault encrypts the card number and stores it securely within the vault, and returns an opaque, non-reversible token. The raw card number MUST NOT be retained by the transaction service after the token is received. If the vault is unavailable or returns an error, the payment submission MUST be rejected immediately and no transaction record created.
- **FR-002a**: System MUST store the card token and a masked representation of the card number (last 4 digits only, all preceding digits replaced with asterisks, e.g., ****-****-****-1234) in the transaction record. No other representation of the card number may be stored in our systems.
- **FR-003**: System MUST discard the CVV and card expiry immediately after submitting them to the tokenization vault; neither value may appear in any stored record or outbound notification.
- **FR-004**: System MUST persist each accepted transaction with a status of PENDING, a unique transaction identifier, the card token, and the masked card number.
- **FR-005**: System MUST return the unique transaction identifier to the caller upon successful submission.
- **FR-006**: System MUST dispatch a new-transaction notification to downstream services (authorization, fraud detection) immediately after persisting the record.
- **FR-007**: System MUST update a transaction's status to AUTHORIZED when an authorization-approved notification is received.
- **FR-008**: System MUST update a transaction's status to DECLINED when an authorization-declined notification is received.
- **FR-009**: System MUST update a transaction's status to FAILED when a system-failure notification is received.
- **FR-010**: System MUST NOT update a transaction that is already in a terminal state (AUTHORIZED, DECLINED, or FAILED) — such notifications MUST be acknowledged and discarded.
- **FR-011**: Every status-update notification handler MUST record a unique notification identifier before applying any state change, and MUST skip processing if that identifier has already been recorded.
- **FR-012**: System MUST never store, log, or transmit a raw card number at any point in any code path.
- **FR-013**: System MUST reject any payment request that is missing a required field, returning a descriptive validation error.
- **FR-014**: System MUST handle concurrent payment submissions without creating duplicate transaction records for the same logical request.

### Key Entities *(include if feature involves data)*

- **Transaction**: Represents a single card payment attempt. Key attributes: unique transaction identifier, card token (opaque, returned by the external encryption vault), masked card number (last 4 digits only, e.g., ****-****-****-1234), cardholder name, payment amount, currency code, status (PENDING / AUTHORIZED / DECLINED / FAILED), created timestamp, last updated timestamp. The raw card number is never an attribute of this entity.
- **Card Token**: An opaque identifier returned by the external encryption vault after it encrypts and stores the raw card number. Once stored on the transaction, the raw card number is discarded. The vault is the sole holder of the encrypted card number; the transaction service holds only the token and the masked number. The same physical card may produce a new token on each transaction (token-per-transaction model).
- **Notification Record**: A record of each processed inbound status-update notification, keyed by a unique notification identifier. Used to enforce idempotency — if a record exists, the notification has already been processed.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A card payment submission is acknowledged and a transaction identifier is returned in under 500 milliseconds at the 95th percentile under normal operating load.
- **SC-002**: Zero raw card numbers appear in any transaction record in our systems, any log output, or any outbound notification — verifiable by automated pattern scanning across all storage and log fixtures in the test suite. (Raw card numbers are stored exclusively within the dedicated external encryption vault, which is purpose-built for secure encrypted storage and is outside our application database.)
- **SC-003**: Delivering the same status-update notification 10 times in succession results in exactly one state change, with all subsequent deliveries acknowledged without error — verifiable by test.
- **SC-004**: Every class in the transaction service has 100% line and branch test coverage as reported by the build pipeline; a coverage report below 100% causes the build to fail.
- **SC-005**: All four transaction statuses (PENDING, AUTHORIZED, DECLINED, FAILED) and all four user stories have explicit passing tests before any implementation begins.
- **SC-006**: Concurrent submission of 100 identical payment requests results in exactly one persisted transaction record — verifiable by concurrency test.

## Assumptions

- The api-gateway authenticates and authorizes the caller before forwarding payment requests to the transaction service; the transaction service does not perform caller authentication.
- Currency is expressed as a 3-letter ISO 4217 code (e.g., USD, EUR, GBP).
- Payment amount is expressed as a positive decimal in the major currency unit (e.g., 19.99 for $19.99 USD).
- Tokenization is delegated to an external encryption vault service called synchronously during payment submission. The vault encrypts the card number, stores it internally, and returns an opaque token. The selected vault is HashiCorp Vault (Transit Secrets Engine); Spring Vault (spring-vault-core) is the integration library. HashiCorp Vault runs as a Docker container in local development and as a Kubernetes pod on GCP in production.
- If the external tokenization vault is unavailable or returns an error, payment submission fails immediately — the system does not retry the vault call as part of the synchronous payment request (retry policy is a separate operational concern).
- The card token model is token-per-transaction: the vault generates a new token for each payment submission. The same physical card number will yield different tokens on different transactions.
- The masked card number format is the last 4 digits of the card number with all preceding digits replaced by asterisks, formatted as ****-****-****-XXXX.
- Downstream notification delivery uses at-least-once semantics; idempotency on consumers (FR-011) is the mitigation for duplicate deliveries.
- If the downstream notification cannot be dispatched after the transaction record is persisted, the transaction remains PENDING and the dispatch failure is logged as a recoverable error (retry handled by the messaging infrastructure).
- A transaction in a terminal state (AUTHORIZED, DECLINED, FAILED) cannot be moved to any other status; terminal state transitions are irreversible.
- The transaction service exposes its payment submission endpoint to the api-gateway only; it is not directly accessible by external clients.
- All tests are written and confirmed to fail before any implementation code is written (constitution Principle II).
