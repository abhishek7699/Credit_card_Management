# Feature Specification: API Gateway

**Feature Branch**: `007-api-gateway`

**Created**: 2026-06-17

**Status**: Draft

**Input**: User description: "Build the api-gateway: external REST entry point for the payment gateway. Validates JWT tokens issued by a separate auth-service (Spring Authorization Server, OAuth2). Enforces role-based authorization (MERCHANT, ADMIN, SUPPORT roles) via Spring Security. Implements Redis-backed rate limiting per client. Implements Resilience4j circuit breaker for calls to downstream services, with fallback responses when a service is unavailable. Routes authenticated REST requests to transaction-service via gRPC and to reporting-service via internal REST. Rejects unauthenticated or unauthorized requests with proper HTTP status codes. TDD with 100% coverage mandatory."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Merchants Submit Payment Transactions (Priority: P1)

A merchant's system (POS terminal, e-commerce platform, or payment application) sends a payment transaction through the gateway using a valid credential. The gateway confirms the credential is legitimate, verifies the merchant has permission to submit transactions, and forwards the request to the payment processing service. The merchant's system receives the processing result — approved or declined.

**Why this priority**: Transaction submission is the core revenue-generating action of the payment gateway. Nothing else matters if merchants cannot process payments.

**Independent Test**: Can be fully tested by submitting a transaction with a valid merchant credential and verifying that an approval or decline response is returned. No other feature needs to be built first.

**Acceptance Scenarios**:

1. **Given** a merchant has a valid, unexpired credential with the MERCHANT role, **When** they submit a well-formed transaction request, **Then** the gateway forwards it to the payment processor and returns the processing result with a 200 OK status.
2. **Given** a merchant submits a transaction with an expired credential, **When** the gateway validates the credential, **Then** the gateway rejects the request with a 401 Unauthorized response and does not forward the transaction.
3. **Given** a support agent (who has read-only access) submits a transaction request, **When** the gateway checks the agent's permissions, **Then** the gateway rejects the request with a 403 Forbidden response.
4. **Given** a credential with the MERCHANT role but issued by an unrecognized authority, **When** the gateway validates it, **Then** the gateway returns a 401 Unauthorized response.

---

### User Story 2 - Users Access Transaction History and Settlement Reports (Priority: P1)

Merchants, administrators, and support agents query transaction history and settlement data through the gateway. Merchants may only view their own data. Administrators and support agents have access to all data. The gateway validates credentials, enforces these access boundaries, and retrieves the requested information.

**Why this priority**: Read access to transaction and settlement data is essential for reconciliation, dispute resolution, and daily operations. This is a core workflow for all three user roles.

**Independent Test**: Can be fully tested by querying transaction history with credentials from each role (MERCHANT, ADMIN, SUPPORT) and verifying that each role sees only the data they are permitted to see.

**Acceptance Scenarios**:

1. **Given** a merchant with a valid credential queries transaction history, **When** the gateway processes the request, **Then** only that merchant's own transactions are returned.
2. **Given** an administrator with a valid credential queries transaction history, **When** the gateway processes the request, **Then** all transactions across all merchants are returned.
3. **Given** a support agent with a valid credential queries a settlement report, **When** the gateway processes the request, **Then** the settlement report is returned (read-only access).
4. **Given** a request with no credential, **When** the gateway receives a history or report query, **Then** a 401 Unauthorized response is returned immediately.
5. **Given** a merchant queries another merchant's transaction history directly by ID, **When** the gateway checks access, **Then** a 403 Forbidden response is returned.

---

### User Story 3 - Per-Client Usage Limits Prevent Abuse (Priority: P2)

Each client (identified by their credential) is limited in how many requests they can make within a time window. When a client exceeds this limit, the gateway temporarily rejects further requests with a clear response indicating the client should wait. Clients within their limit are unaffected.

**Why this priority**: Rate limiting protects the payment system from accidental overload and deliberate abuse. It ensures fair access for all clients and protects downstream services from being overwhelmed.

**Independent Test**: Can be fully tested by sending requests at a rate exceeding the configured limit and verifying that requests beyond the threshold receive a 429 Too Many Requests response while requests below the threshold succeed.

**Acceptance Scenarios**:

1. **Given** a client has not exceeded their request limit, **When** they make a request, **Then** the request is processed normally.
2. **Given** a client has exceeded their per-minute request quota, **When** they submit another request, **Then** the gateway returns a 429 Too Many Requests response with a header indicating when the client may retry.
3. **Given** two different clients each at their limit, **When** both make additional requests simultaneously, **Then** both receive 429 responses independently (limits are per client, not global).
4. **Given** a client received a 429 and waited for the rate window to reset, **When** they submit the next request, **Then** the request is accepted normally.

---

### User Story 4 - Gateway Remains Responsive When Downstream Services Fail (Priority: P2)

When the payment processing service or the reporting service is unavailable or responding too slowly, the gateway does not hang or crash. Instead, it immediately returns a meaningful response indicating temporary unavailability. The gateway automatically resumes forwarding requests to the service once it recovers.

**Why this priority**: A gateway that hangs indefinitely when downstream services fail makes the entire payment platform appear down. Fast failure and automatic recovery protect both merchants and the business.

**Independent Test**: Can be fully tested by simulating a downstream service outage and verifying that the gateway returns a timely service-unavailable response rather than timing out. Recovery is tested by restoring the downstream service and confirming normal behavior resumes automatically.

**Acceptance Scenarios**:

1. **Given** the payment processing service is unavailable, **When** a merchant submits a transaction, **Then** the gateway returns a 503 Service Unavailable response within 2 seconds, without attempting to retry indefinitely.
2. **Given** the reporting service is unavailable, **When** a user requests transaction history, **Then** the gateway returns a 503 Service Unavailable response promptly.
3. **Given** the gateway has been returning 503 for a downstream service, **When** the downstream service recovers, **Then** the gateway automatically resumes routing requests to it without requiring a restart.
4. **Given** a downstream service is experiencing intermittent slowness, **When** the failure rate exceeds the configured threshold, **Then** the gateway stops forwarding requests to that service temporarily to prevent cascading failures.

---

### Edge Cases

- What happens when a credential is well-formed and signed but the user's account has been revoked mid-session?
- How does the gateway handle a credential that is valid but belongs to a deactivated merchant account?
- What happens if the rate limiting store is temporarily unavailable — does the gateway fail open (allow requests) or fail closed (deny requests)?
- What happens when a request contains a valid credential but the client's IP address changes between requests?
- How does the gateway respond when a downstream service returns an unexpected error format?
- What happens when the gateway itself is restarting — do in-flight requests complete or fail?

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: The gateway MUST reject any request that does not include a valid, unexpired credential with a 401 Unauthorized response and MUST NOT forward the request to any downstream service.
- **FR-002**: The gateway MUST validate credentials against the authorization authority to confirm they have not been tampered with and were issued by a trusted source.
- **FR-003**: The gateway MUST enforce role-based access control, ensuring MERCHANT role clients can only submit transactions and view their own data, ADMIN role clients can access all data and operations, and SUPPORT role clients have read-only access to all data.
- **FR-004**: The gateway MUST return a 403 Forbidden response when a credential is valid but the client's role does not permit the requested operation.
- **FR-005**: The gateway MUST enforce per-client request rate limits and return a 429 Too Many Requests response when a client exceeds their quota, including a response header indicating the retry-after window.
- **FR-006**: The gateway MUST detect when a downstream service is failing and stop forwarding requests to it once failures exceed a configurable threshold, returning a 503 Service Unavailable response instead.
- **FR-007**: The gateway MUST automatically resume routing to a downstream service after a configurable recovery period without requiring a manual restart.
- **FR-008**: The gateway MUST route transaction submission and status requests to the payment processing service.
- **FR-009**: The gateway MUST route transaction history, merchant summary, and settlement report requests to the reporting service.
- **FR-010**: The gateway MUST propagate the client's identity and role information to downstream services so they can apply their own access controls if needed.
- **FR-011**: The gateway MUST log every rejected request (401, 403, 429, 503) with sufficient context for auditing, without logging sensitive credential data.
- **FR-012**: The gateway MUST expose a health check endpoint that reports its own availability and the availability of each downstream service.

### Key Entities

- **Client Credential**: A time-limited token asserting a client's identity and role (MERCHANT, ADMIN, or SUPPORT). Issued externally and validated by the gateway on every request.
- **Client**: An identified caller (merchant system, admin tool, support interface) distinguished by their credential. Subject to rate limiting per identity.
- **Rate Limit Quota**: The number of requests a client may make within a defined time window. Tracked per client identity across all gateway instances.
- **Downstream Service**: An internal service (payment processor, reporting) that the gateway routes requests to. Each has an associated health state tracked by the circuit state machine.
- **Circuit State**: The current health classification of a downstream service: Closed (normal routing), Open (routing suspended, returning fallback), or Half-Open (testing recovery).
- **Route**: A mapping from an incoming request path and method to a specific downstream service and operation.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of requests without valid credentials are rejected at the gateway without reaching any downstream service, verified by integration testing.
- **SC-002**: Role enforcement is 100% accurate — no MERCHANT can access another merchant's data, no SUPPORT role can perform write operations, verified by automated test suite.
- **SC-003**: Clients exceeding their request quota receive a 429 response within 50 milliseconds, measured from request receipt to response.
- **SC-004**: When a downstream service is unavailable, the gateway returns a 503 fallback response within 2 seconds for 99% of requests, preventing indefinite hangs.
- **SC-005**: The gateway automatically recovers routing to a downstream service within 30 seconds of the service becoming healthy again, without manual intervention.
- **SC-006**: The test suite achieves 100% instruction and branch coverage, confirmed by the build pipeline on every change.
- **SC-007**: All security rejection events (401, 403) are logged with timestamp, client identity (non-sensitive), and request path for audit purposes.

## Assumptions

- A separate authentication service already issues and manages credentials (tokens) using industry-standard OAuth2 flows. The gateway only validates credentials — it does not issue them.
- The credential format is a signed bearer token with claims for client identity and role. The gateway has access to the public keys needed to verify signatures.
- When the rate limiting store is temporarily unavailable, the gateway fails open (allows requests) to avoid blocking legitimate traffic. This is a business decision prioritizing availability over strict quota enforcement during infrastructure failures.
- MERCHANT role clients are identified by a merchant ID embedded in their credential. The gateway uses this to scope data access automatically — merchants do not need to pass their merchant ID separately in requests.
- The SUPPORT role has identical read permissions to ADMIN but cannot perform any write or submission operations.
- Rate limit quotas are configured uniformly across all clients by role. Per-client custom quotas are out of scope for this version.
- The gateway is not responsible for TLS termination at the network edge; that is handled by the infrastructure layer (load balancer). The gateway receives plaintext HTTP within the secure network.
- Downstream service addresses are provided via environment configuration and are not dynamically discovered at runtime.
- The payment processing service and the reporting service each enforce their own data authorization as a second layer of defense, in addition to the gateway's enforcement.
