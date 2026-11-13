# Feature Specification: Mastercard Gateway Test Environment as Payment Processor

**Feature Branch**: `014-mastercard-mtf-processor`

**Created**: 2026-11-12

**Status**: Draft

**Input**: User description: "Integrate Mastercard Gateway's test environment (MTF) as a real payment processor for the payment core, keeping simulated authorization for tests. Minimal scope, built in four phases: (1) refactor only, PaymentProcessor interface + SimulatedProcessor, no behavior change; (2) MastercardGatewayProcessor selected by config, tested against WireMock; (3) enable real MTF calls from env-var credentials, never committed or logged; (4) settlement batch calls CAPTURE before SETTLED when Mastercard is active. Hosted Session approach: card details go to Mastercard, backend gets only a session ID; transactionId used as order/transaction identifiers; timeouts/unknown results are resolved by querying Mastercard before acting; never log session payloads, card data, or credentials; e2e and CI stay on the simulator by default. Out of scope: refunds, voids, fraud reversals, frontend card form, GKE, real cards."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Refactor the approval decision behind an interface (Priority: P1)

The platform team can change how a payment is approved without touching the payment flow. Today approval is decided inside the authorization step using the card accounts table. After this change that same decision is made by a processor component, and the existing behavior is kept exactly.

**Why this priority**: Every later phase depends on there being a seam where a real processor can plug in. It carries no new behavior, so it is the lowest-risk first step.

**Independent Test**: Run the existing test suite and the end-to-end script with no changes to them. Both pass, and every transaction gets the same outcome as before.

**Acceptance Scenarios**:

1. **Given** the existing test suite and end-to-end script, **When** they run against the refactored service, **Then** all pass without any test being edited.
2. **Given** a card with enough available credit, **When** a payment is submitted, **Then** it is approved and the card's available credit drops by the payment amount, exactly as before.
3. **Given** a card with insufficient credit, a blocked card, or an unknown card, **When** a payment is submitted, **Then** it is declined with the same reason codes as before.

---

### User Story 2 - Route approvals to Mastercard Gateway by configuration (Priority: P2)

Operators can switch the payment core from the simulator to Mastercard Gateway by configuration alone. The simulator stays the default. The Mastercard path is verified in automated tests against a stand-in for the gateway, so no real credentials are needed.

**Why this priority**: It proves the request and response mapping (approve, decline, timeout) before any live call is made.

**Independent Test**: Run the Mastercard path with the gateway stand-in configured to return approve, decline, timeout, and malformed responses. Each produces the expected lifecycle outcome, and the simulator default still produces the same results as before.

**Acceptance Scenarios**:

1. **Given** the processor setting is unset, **When** a payment is authorized, **Then** the simulator decides the outcome.
2. **Given** the processor setting selects Mastercard Gateway and the gateway approves, **When** a payment is authorized, **Then** the transaction becomes AUTHORIZED.
3. **Given** the gateway declines, **When** a payment is authorized, **Then** the transaction becomes DECLINED and the decline reason from the gateway is recorded.
4. **Given** the gateway call times out or returns an unknown result, **When** a payment is authorized, **Then** the system asks the gateway for the transaction's status and acts on that answer. The transaction is not marked DECLINED on the timeout alone.
5. **Given** a payment is retried (the same transaction is processed again), **When** the gateway receives the repeat request, **Then** it cannot approve or capture the same transaction twice.

---

### User Story 3 - Run real payments against the Mastercard test environment (Priority: P3)

A tester can submit a payment with a session identifier, instead of card details, and see it approved or declined by the Mastercard test environment. Credentials come from environment variables and are never stored in the repository or written to logs.

**Why this priority**: It is the first end-to-end check against a real external system, and it depends on credentials the owner provides separately.

**Independent Test**: With test credentials set in the environment, submit a payment through the public interface using a test session. The transaction reaches AUTHORIZED or DECLINED according to the test card used, and no credential or card value appears in any log.

**Acceptance Scenarios**:

1. **Given** valid test credentials in the environment and a test session identifier, **When** a payment is submitted through the public interface, **Then** the response is accepted with a transaction identifier and the transaction later reaches AUTHORIZED or DECLINED.
2. **Given** a request that includes raw card details instead of a session identifier, **When** it is submitted through the new session path, **Then** it is rejected and no card details are stored or forwarded.
3. **Given** the service logs, **When** a payment has been processed, **Then** no log line contains a card number, expiry, security code, session payload, or credential.

---

### User Story 4 - Capture approved payments at settlement (Priority: P4)

When the Mastercard path is active, the end-of-day settlement captures each approved payment with Mastercard before marking it settled. A payment whose capture fails is not marked settled.

**Why this priority**: Settlement only matters once real money movement is in play, so it comes after the authorization path works.

**Independent Test**: Run the settlement batch with the gateway stand-in. Every AUTHORIZED payment is captured once and then marked SETTLED. A capture that fails leaves the payment unsettled and is reported.

**Acceptance Scenarios**:

1. **Given** AUTHORIZED payments and the Mastercard path active, **When** the settlement batch runs, **Then** each payment is captured once and then marked SETTLED.
2. **Given** the simulator is active, **When** the settlement batch runs, **Then** it behaves exactly as it does today, with no gateway call.
3. **Given** a capture fails or its result is unknown, **When** the batch runs, **Then** the payment stays AUTHORIZED, the outcome is checked with the gateway, and the failure is reported for follow-up.

---

### Edge Cases

- A gateway call times out after the gateway actually approved the payment: the system queries the gateway for the transaction's status before deciding anything, and does not mark it DECLINED.
- The same payment is submitted again after a timeout: the repeat uses the same transaction identifier, so the gateway cannot approve it twice.
- A session identifier expires or has already been used before the payment is submitted: the payment is declined or rejected with a clear reason, and nothing is retried silently.
- The gateway returns a response in a format the system does not recognize: treated as an unknown result, not as a decline.
- Credentials are missing or wrong while the Mastercard path is active: the service refuses to start the path, or each payment fails with a clear configuration error. It never falls back to the simulator silently.
- A transaction that is already AUTHORIZED is later reported as declined by the gateway: the stored outcome is not overwritten by a later conflicting result without an explicit reconciliation step.
- Settlement runs while the Mastercard path is active but the gateway is unreachable: no payment is marked SETTLED without a successful capture.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The payment core MUST decide approval through a single processor abstraction, with the existing card-account logic moved behind it as the simulator, without changing behavior.
- **FR-002**: The active processor MUST be chosen by configuration, and the simulator MUST be the default when no choice is set.
- **FR-003**: The Mastercard path MUST map an approved authorization to AUTHORIZED, and a declined authorization to DECLINED with the gateway's reason recorded.
- **FR-004**: The Mastercard path MUST use the transaction identifier as the order and transaction identifiers sent to the gateway, so that a repeated request cannot authorize or capture twice.
- **FR-005**: When a gateway call times out or returns an unknown result, the system MUST query the gateway for the transaction's status and act on that result. It MUST NOT mark the transaction DECLINED on the timeout alone.
- **FR-006**: The public payment interface MUST accept a session identifier in place of card details, and MUST reject requests that include card details on that path.
- **FR-007**: The payment core MUST NOT store, log, or publish card numbers, expiry dates, security codes, session payloads, or processor credentials.
- **FR-008**: Processor credentials MUST be read from environment variables only, and MUST NOT appear in the repository, configuration files committed to version control, or logs.
- **FR-009**: When the Mastercard path is active, the settlement batch MUST capture each AUTHORIZED payment with the gateway before marking it SETTLED.
- **FR-010**: A payment whose capture fails or returns an unknown result MUST NOT be marked SETTLED.
- **FR-011**: The end-to-end script and the continuous integration pipeline MUST continue to run against the simulator unless explicitly configured otherwise.
- **FR-012**: Automated tests for the Mastercard path MUST run against a local stand-in for the gateway and MUST NOT require credentials or network access to Mastercard.

### Key Entities *(include if feature involves data)*

- **Transaction**: The payment record. Keeps its existing lifecycle (PENDING, AUTHORIZED, DECLINED, FAILED, SETTLED). Gains a record of the gateway's decline reason and of the gateway's transaction reference, but never card data.
- **Payment session**: A short-lived identifier created by Mastercard that stands for card details already collected. The backend receives and forwards only this identifier.
- **Processor decision**: The outcome from the active processor for one authorization or capture request: approved, declined, or unknown, plus a reason and a reference.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: The existing automated test suite and end-to-end script pass unchanged after phase 1, with zero behavior differences in approval outcomes across the existing test scenarios.
- **SC-002**: In automated tests, 100% of gateway timeout and unknown-result scenarios end with the payment's status confirmed against the gateway rather than marked DECLINED.
- **SC-003**: In automated tests, a repeated submission of the same transaction produces at most one approval and at most one capture, in every tested case.
- **SC-004**: A search of all service logs and stored records after a full test run finds zero card numbers, security codes, session payloads, or credentials.
- **SC-005**: With test credentials configured, a tester can submit a payment with a session identifier and see it reach AUTHORIZED or DECLINED as expected for the test card used, without changing any code.
- **SC-006**: With the Mastercard path active, no payment reaches SETTLED without a successful capture in the test runs.

## Assumptions

- The payment core's existing behavior, including the simulator's approval rules, is the baseline that phase 1 must reproduce exactly.
- The Hosted Session approach is used as specified. Card details are entered by the cardholder (or by a test tool acting for them) directly with Mastercard. The backend receives only the session identifier.
- **Verified from Mastercard documentation (retrieved during specification):**
  - Create Session is a POST to `/api/rest/version/{version}/merchant/{merchantId}/session`, authenticated with HTTP Basic (blank user name, API password as the password). It returns a `session` string and a `result` of SUCCESS, PENDING, FAILURE, or UNKNOWN. Sources: [Create Session, REST-JSON v12](https://test-gateway.mastercard.com/api/documentation/apiDocumentation/rest-json/version/12/operation/Session:%20Create%20Session.html?locale=en_US).
  - The Update Session operation can carry card data in `sourceOfFunds.provided.card`, which means server-side population of a session is possible in principle. Its PCI implications are not documented, so this spec does not rely on it for the backend path. Sources: [Update Session, NVP v32](https://network.gateway.mastercard.com/api/documentation/apiDocumentation/nvp/version/32/operation/Session:%20Update%20Session.html?locale=en_US), [Update Session, NVP v77 (MTF)](https://mtf.gateway.mastercard.com/api/documentation/apiDocumentation/nvp/version/77/operation/Session:%20Update%20Session.html?locale=et_ET).
  - The Authorize operation is a PUT to `/api/rest/version/{version}/merchant/{merchantId}/order/{orderId}/transaction/{transactionId}`, and the response includes a `response.gatewayCode` and a `transaction.id`. Sources: [Authorize, REST-JSON v12](https://ap-gateway.mastercard.com/api/documentation/apiDocumentation/rest-json/version/12/operation/Transaction:%20%20Authorize.html?locale=en_US), [Authorize, REST-JSON latest](https://eu-gateway.mastercard.com/api/documentation/apiDocumentation/rest-json/version/latest/operation/Transaction:%20%20Authorize.html?locale=en_US).
  - Test merchant IDs are prefixed `TEST`, and the test card page says that prefix is required for test cards to work. Source: [Mastercard Gateway test cards](https://developer.mastercard.com/mastercard-gateway/documentation/testing/test-cards/).
- **Not verified (to confirm against the test merchant's own documentation before phase 3):**
  - The exact approve and decline outcome rules for test cards. Partial retrieval suggested test card 5123450000000008 and 2223000000000007 work across regions, that an expiry of 01/39 approves and 05/39 declines, and that CVV 102 returns NO_MATCH. These came from search excerpts, not the full official test-cards table. Amount-based outcome rules were not found for the Mastercard test environment.
  - The API version this merchant account is enabled for. Versions 12, 32, 62, 77, and "latest" all appear in Mastercard's documentation; the version is pinned in phase 2 to the one the merchant account confirms.
- The e2e script and continuous integration keep using the simulator. They are not switched to the Mastercard path in this feature.
- Refunds, voids, fraud-triggered reversals, the frontend card form, GKE deployment, and any real card or real money movement are out of scope.
- Phase 3 starts only after the owner supplies test credentials through environment variables.
