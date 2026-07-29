# Data Model: API Gateway

**Feature**: `specs/007-api-gateway`
**Date**: 2026-06-17

> The API Gateway is **stateless** — it owns no CockroachDB schema and runs no Flyway migrations.
> All persistent state lives in Redis (rate limit buckets) or in external services.
> The entities below are logical runtime constructs, not database tables.

---

## 1. ClientPrincipal (runtime, per-request)

Extracted from the validated JWT on each request. Never persisted by the gateway.

| Field | Type | Source | Notes |
|-------|------|--------|-------|
| `subject` | String | JWT `sub` | Unique client identifier; used as rate limit key |
| `roles` | Set\<String\> | JWT `authorities` | One of: `ROLE_MERCHANT`, `ROLE_ADMIN`, `ROLE_SUPPORT` |
| `merchantId` | String (nullable) | JWT `merchantId` claim | Present only when role is `ROLE_MERCHANT` |
| `issuedAt` | Instant | JWT `iat` | Used to detect clock-skew issues |
| `expiresAt` | Instant | JWT `exp` | Spring Security validates this automatically |

**Validation rules**:
- `roles` must contain exactly one of `ROLE_MERCHANT`, `ROLE_ADMIN`, `ROLE_SUPPORT`
- If `roles` contains `ROLE_MERCHANT`, `merchantId` MUST be non-null and non-empty
- JWT signature MUST be verifiable against the auth-service's JWKS endpoint

---

## 2. RateLimitBucket (Redis, TTL-scoped)

Managed by Spring Cloud Gateway's `RedisRateLimiter` (token-bucket algorithm, Redis Lua script).
Key pattern: `request_rate_limiter.{clientSubject}.{routeId}`

| Field | Type | Notes |
|-------|------|-------|
| `clientKey` | String | JWT `sub` value (rate limit key) |
| `tokensRemaining` | int | Current tokens in the bucket; decremented on each request |
| `lastRefillTimestamp` | long (epoch ms) | When the bucket was last replenished |
| `replenishRate` | int | Tokens added per second (configured per route) |
| `burstCapacity` | int | Maximum bucket size (configured per route) |

**State lifecycle**: Created on first request from a client. TTL auto-expires after idle window. Refilled at `replenishRate` per second up to `burstCapacity`.

---

## 3. CircuitBreakerState (in-memory, per instance)

Managed by Resilience4j. Two named circuit breakers: `transaction-service-cb`, `reporting-service-cb`.

| Field | Type | Notes |
|-------|------|-------|
| `name` | String | `transaction-service-cb` or `reporting-service-cb` |
| `state` | Enum | `CLOSED` (normal), `OPEN` (blocking), `HALF_OPEN` (testing recovery) |
| `failureCount` | int | Failures within the sliding window |
| `successCount` | int | Successes in HALF_OPEN state |
| `lastTransitionAt` | Instant | When state last changed |
| `nextAttemptAt` | Instant | When OPEN → HALF_OPEN transition is allowed |

**State transitions**:
```
CLOSED → OPEN       : when failureCount/windowSize ≥ 50% (10-call sliding window)
OPEN   → HALF_OPEN  : after 30 seconds (waitDurationInOpenState)
HALF_OPEN → CLOSED  : after 3 consecutive successes
HALF_OPEN → OPEN    : after any failure
```

**Fallback response** (OPEN state):
- Transaction routes: HTTP 503 with `{"error": "Transaction service temporarily unavailable. Please retry shortly."}`
- Reporting routes: HTTP 503 with `{"error": "Reporting service temporarily unavailable. Please retry shortly."}`

---

## 4. RouteDefinition (configured, not runtime)

Logical definition of how incoming requests map to downstream services. Defined in `application.yml`.

| Route ID | Predicate | Target | Auth |
|----------|-----------|--------|------|
| `transaction-submit` | `POST /api/v1/transactions` | gRPC `InitiateTransaction` | MERCHANT, ADMIN |
| `transaction-get` | `GET /api/v1/transactions/{id}` | gRPC `GetTransaction` | MERCHANT (own), ADMIN, SUPPORT |
| `reporting-transactions` | `GET /api/v1/reporting/transactions` | REST `/internal/reporting/transactions` | MERCHANT (scoped), ADMIN, SUPPORT |
| `reporting-merchant-summary` | `GET /api/v1/reporting/merchants/{merchantId}/summary` | REST `/internal/reporting/merchants/{merchantId}/summary` | MERCHANT (own), ADMIN, SUPPORT |
| `reporting-all-merchants` | `GET /api/v1/reporting/merchants/summary` | REST `/internal/reporting/merchants/summary` | ADMIN, SUPPORT |
| `reporting-settlement-batch` | `GET /api/v1/reporting/settlements/{batchId}` | REST `/internal/reporting/settlements/{batchId}` | ADMIN, SUPPORT |
| `reporting-merchant-settlement` | `GET /api/v1/reporting/settlements/{batchId}/merchants/{merchantId}` | REST `/internal/reporting/settlements/{batchId}/merchants/{merchantId}` | ADMIN, SUPPORT |

---

## 5. IdentityHeaders (propagated downstream, per-request)

Headers injected by `IdentityPropagationFilter` into forwarded requests.

| Header | Value | Present when |
|--------|-------|--------------|
| `X-User-Sub` | JWT `sub` | Always (all authenticated requests) |
| `X-User-Role` | `MERCHANT`, `ADMIN`, or `SUPPORT` | Always |
| `X-Merchant-Id` | JWT `merchantId` claim value | MERCHANT role only |

These headers allow downstream services to apply their own access control without re-validating the JWT.

---

## 6. TransactionRequest / TransactionResponse (REST DTOs, gateway-owned)

The gateway defines its own request/response DTOs for the external REST API. These are separate from the proto-generated types used in gRPC calls internally.

### POST /api/v1/transactions — Request body

| Field | Type | Required | Constraints |
|-------|------|----------|-------------|
| `cardNumber` | String | Yes | 13-19 digits, no spaces/dashes |
| `cardExpiry` | String | Yes | Format: MM/YY |
| `cvv` | String | Yes | 3-4 digits |
| `cardholderName` | String | Yes | Non-blank |
| `amount` | String | Yes | Decimal string, e.g., "19.99"; positive |
| `currencyCode` | String | Yes | ISO 4217, 3 letters |

### POST /api/v1/transactions — Response body (201 Created)

| Field | Type |
|-------|------|
| `transactionId` | String (UUID) |
| `status` | String (`PENDING`) |

### GET /api/v1/transactions/{id} — Response body (200 OK)

| Field | Type |
|-------|------|
| `transactionId` | String (UUID) |
| `maskedCardNumber` | String (e.g., `****-****-****-1234`) |
| `cardholderName` | String |
| `amount` | String |
| `currencyCode` | String |
| `status` | String (PENDING, AUTHORIZED, DECLINED, FAILED) |
| `createdAt` | ISO-8601 timestamp |
| `updatedAt` | ISO-8601 timestamp |

**Security**: `cardToken` is NEVER returned. `maskedCardNumber` is the only card-related field in the response (matches what transaction-service gRPC returns per the proto contract).
