# Quickstart & Integration Scenarios: API Gateway

**Feature**: `specs/007-api-gateway`
**Date**: 2026-06-17

These scenarios cover the integration test cases required to validate the full gateway behavior. Each scenario can be tested independently with: Testcontainers (Redis), an in-process gRPC server (mocking transaction-service), and WireMock (mocking reporting-service HTTP).

---

## Infrastructure Setup (Integration Tests)

```java
// Redis: Testcontainers
@Container static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
    .withExposedPorts(6379);

// gRPC transaction-service: in-process mock server
InProcessServerBuilder.forName("transaction-test")
    .addService(new MockTransactionServiceImpl())
    .build().start();

// Reporting-service: WireMock HTTP mock
WireMockServer wiremock = new WireMockServer(WireMockConfiguration.options().dynamicPort());

// JWT: RSA key pair generated in test; JWKS endpoint served by mock
RsaJsonWebKey rsaJwk = RsaJwkGenerator.generateJwk(2048);
// Gateway configured with jwks-uri pointing to WireMock endpoint
```

---

## Scenario Group 1: JWT Authentication

### 1.1 — No token → 401

```
Request:  POST /api/v1/transactions
Headers:  (no Authorization header)
Expected: HTTP 401
Body:     { "error": "Unauthorized", "message": "..." }
```

### 1.2 — Expired token → 401

```
Request:  POST /api/v1/transactions
Headers:  Authorization: Bearer <expired-jwt>
Expected: HTTP 401
```

### 1.3 — Valid MERCHANT token → 201 (transaction submitted)

```
Request:  POST /api/v1/transactions
Headers:  Authorization: Bearer <valid-merchant-jwt>
Body:     { "cardNumber": "4111111111111111", "cardExpiry": "12/26",
            "cvv": "123", "cardholderName": "Jane Smith",
            "amount": "99.99", "currencyCode": "USD" }
Expected: HTTP 201
Body:     { "transactionId": "...", "status": "PENDING" }
gRPC mock: InitiateTransaction called once; response returns transactionId + PENDING
```

### 1.4 — Unknown issuer (invalid JWKS signature) → 401

```
Request:  POST /api/v1/transactions
Headers:  Authorization: Bearer <jwt-signed-with-unknown-key>
Expected: HTTP 401
```

---

## Scenario Group 2: Role-Based Authorization

### 2.1 — SUPPORT role submits transaction → 403

```
Request:  POST /api/v1/transactions
Headers:  Authorization: Bearer <support-jwt>
Expected: HTTP 403
Body:     { "error": "Forbidden", "message": "..." }
gRPC mock: InitiateTransaction NOT called
```

### 2.2 — ADMIN submits transaction → 201

```
Request:  POST /api/v1/transactions
Headers:  Authorization: Bearer <admin-jwt>
Expected: HTTP 201
```

### 2.3 — MERCHANT accesses all-merchants summary → 403

```
Request:  GET /api/v1/reporting/merchants/summary
Headers:  Authorization: Bearer <merchant-jwt>
Expected: HTTP 403
WireMock: not called
```

### 2.4 — ADMIN accesses all-merchants summary → 200

```
Request:  GET /api/v1/reporting/merchants/summary
Headers:  Authorization: Bearer <admin-jwt>
WireMock: GET /internal/reporting/merchants/summary → 200
Expected: HTTP 200
```

### 2.5 — SUPPORT accesses settlement batch → 200

```
Request:  GET /api/v1/reporting/settlements/batch-001
Headers:  Authorization: Bearer <support-jwt>
WireMock: GET /internal/reporting/settlements/batch-001 → 200
Expected: HTTP 200
```

---

## Scenario Group 3: Merchant Data Scoping

### 3.1 — MERCHANT queries own transaction → 200

```
JWT merchantId: merchant-001
Request:  GET /api/v1/transactions/tx-001
gRPC mock: GetTransaction returns { merchantId: "merchant-001", ... }
Expected: HTTP 200
```

### 3.2 — MERCHANT queries another merchant's transaction → 403

```
JWT merchantId: merchant-001
Request:  GET /api/v1/transactions/tx-002
gRPC mock: GetTransaction returns { merchantId: "merchant-002", ... }
Expected: HTTP 403
gRPC mock was called (merchant scoping check is post-response)
```

### 3.3 — MERCHANT queries own merchant summary (matching merchantId) → 200

```
JWT merchantId: merchant-001
Request:  GET /api/v1/reporting/merchants/merchant-001/summary
WireMock: GET /internal/reporting/merchants/merchant-001/summary → 200
Expected: HTTP 200
```

### 3.4 — MERCHANT queries another merchant's summary → 403

```
JWT merchantId: merchant-001
Request:  GET /api/v1/reporting/merchants/merchant-002/summary
Expected: HTTP 403
WireMock: not called (gateway rejects before forwarding)
```

### 3.5 — MERCHANT queries transactions with injected merchantId filter

```
JWT merchantId: merchant-001
Request:  GET /api/v1/reporting/transactions?merchantId=merchant-002&from=...
Expected: HTTP 200 (gateway overrides merchantId to merchant-001 in forwarded request)
WireMock: called with merchantId=merchant-001 in query params (not merchant-002)
```

---

## Scenario Group 4: Rate Limiting

### 4.1 — Requests within quota → all succeed

```
JWT sub: client-A
Send 10 POST /api/v1/transactions in 1 second (within burst capacity)
Expected: all return HTTP 201
Redis: token bucket decremented appropriately
```

### 4.2 — Request exceeding quota → 429

```
JWT sub: client-B
Send 21 POST /api/v1/transactions within 1 second (burst=20)
Expected: first 20 → HTTP 201, request 21 → HTTP 429
Response header: X-RateLimit-Retry-After-Seconds: 1
```

### 4.3 — Different clients have independent quotas

```
client-A: sends 20 requests (hits limit) → 429
client-B: sends 1 request at same time → 200 (different bucket)
```

---

## Scenario Group 5: Circuit Breaker

### 5.1 — Transaction service unavailable → 503 fallback within 2 seconds

```
gRPC mock: configured to return UNAVAILABLE status for all calls
Send POST /api/v1/transactions (≥5 requests to open circuit)
Expected: HTTP 503
Body: { "error": "Service Unavailable", "message": "Transaction service..." }
Timing: response within 2 seconds
```

### 5.2 — Reporting service unavailable → 503 fallback

```
WireMock: returns connection refused
Request:  GET /api/v1/reporting/transactions
Expected: HTTP 503 with { "error": "Service Unavailable", "message": "Reporting service..." }
```

### 5.3 — Circuit breaker transitions to HALF_OPEN after waitDuration

```
After 5.1 above (circuit OPEN)
Wait 30 seconds (or override waitDurationInOpenState=1s in test config)
Send 1 POST /api/v1/transactions
gRPC mock: now returns success
Expected: HTTP 201 (circuit transitions to HALF_OPEN, then CLOSED after 3 successes)
```

---

## Scenario Group 6: Identity Header Propagation

### 6.1 — X-User-Sub, X-User-Role, X-Merchant-Id headers injected to reporting-service

```
JWT: { sub: "user-123", roles: ["ROLE_MERCHANT"], merchantId: "merchant-001" }
Request: GET /api/v1/reporting/transactions
WireMock: assert received headers:
  X-User-Sub: user-123
  X-User-Role: MERCHANT
  X-Merchant-Id: merchant-001
Expected: HTTP 200
```

### 6.2 — ADMIN role: X-Merchant-Id NOT injected

```
JWT: { sub: "admin-001", roles: ["ROLE_ADMIN"] }
Request: GET /api/v1/reporting/merchants/summary
WireMock: assert X-Merchant-Id header is absent
Expected: HTTP 200
```

---

## Scenario Group 7: Audit Logging

### 7.1 — 401 rejection is logged at WARN level with path and timestamp

```
Send unauthenticated request
Check log output: contains "SECURITY_REJECT", "401", path, timestamp
Check log output: does NOT contain card number or raw JWT value
```

### 7.2 — PAN in request body does NOT appear in logs

```
Send POST /api/v1/transactions with cardNumber: "4111111111111111"
Check all log output: "4111111111111111" absent from every log line
```

---

## Scenario Group 8: Health Endpoint

### 8.1 — All services healthy → 200 UP

```
Request:  GET /actuator/health (no auth required)
Redis:    running
Expected: HTTP 200, { "status": "UP", ... }
```

### 8.2 — Redis down → 503 DOWN

```
Redis:    stopped
Request:  GET /actuator/health
Expected: HTTP 503, { "status": "DOWN", components: { redis: { status: "DOWN" } } }
```
