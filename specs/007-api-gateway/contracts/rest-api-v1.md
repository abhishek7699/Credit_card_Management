# REST API Contract: API Gateway (v1)

**Base path**: `/api/v1`
**Content-Type**: `application/json`
**Auth**: Bearer JWT token (OAuth2) required on all routes except `/actuator/health`
**Port**: 8080 (external HTTP)

---

## Authentication

All requests MUST include an `Authorization` header:

```
Authorization: Bearer <jwt-token>
```

**Error responses for auth failures**:

```json
// 401 Unauthorized — missing, expired, or invalid JWT
{
  "error": "Unauthorized",
  "message": "A valid bearer token is required."
}

// 403 Forbidden — valid JWT but role lacks permission
{
  "error": "Forbidden",
  "message": "Your role does not have permission to perform this action."
}
```

---

## Rate Limiting

When a client exceeds their request quota, the gateway returns:

```
HTTP/1.1 429 Too Many Requests
X-RateLimit-Retry-After-Seconds: 1
Content-Type: application/json

{
  "error": "Too Many Requests",
  "message": "Rate limit exceeded. Please retry after the indicated period."
}
```

---

## Circuit Breaker Fallback

When a downstream service is unavailable (circuit OPEN):

```
HTTP/1.1 503 Service Unavailable
Content-Type: application/json

{
  "error": "Service Unavailable",
  "message": "Transaction service temporarily unavailable. Please retry shortly."
}
```

---

## POST /api/v1/transactions

Submit a new payment transaction.

**Required role**: `MERCHANT` or `ADMIN`

### Request body

```json
{
  "cardNumber": "4111111111111111",
  "cardExpiry": "12/26",
  "cvv": "123",
  "cardholderName": "Jane Smith",
  "amount": "99.99",
  "currencyCode": "USD"
}
```

| Field | Type | Required | Constraints |
|-------|------|----------|-------------|
| `cardNumber` | String | Yes | 13–19 digits, no spaces or dashes |
| `cardExpiry` | String | Yes | Format `MM/YY` |
| `cvv` | String | Yes | 3 or 4 digits |
| `cardholderName` | String | Yes | Non-blank |
| `amount` | String | Yes | Positive decimal string (e.g., "19.99") |
| `currencyCode` | String | Yes | ISO 4217 3-letter code |

### Response — 201 Created

```json
{
  "transactionId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "PENDING"
}
```

### Response — 400 Bad Request (validation failure)

```json
{
  "errors": [
    { "field": "currencyCode", "message": "must not be blank" },
    { "field": "amount", "message": "must be a positive decimal value" }
  ]
}
```

### Response — 401/403/429/503 — see common responses above

---

## GET /api/v1/transactions/{transactionId}

Retrieve the current status of a transaction by its ID.

**Required role**: `MERCHANT` (own transactions only), `ADMIN`, `SUPPORT`

**Merchant scoping**: If the authenticated role is `MERCHANT`, the gateway verifies that the retrieved transaction belongs to the requesting merchant. If it does not, a 403 is returned.

### Path Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `transactionId` | UUID string | The transaction ID returned at submission |

### Response — 200 OK

```json
{
  "transactionId": "550e8400-e29b-41d4-a716-446655440000",
  "maskedCardNumber": "****-****-****-1111",
  "cardholderName": "Jane Smith",
  "amount": "99.99",
  "currencyCode": "USD",
  "status": "AUTHORIZED",
  "createdAt": "2026-06-17T14:30:00Z",
  "updatedAt": "2026-06-17T14:30:01Z"
}
```

### Response — 404 Not Found

```json
{
  "error": "Not Found",
  "message": "Transaction not found: 550e8400-e29b-41d4-a716-446655440000"
}
```

### Response — 401/403/429/503 — see common responses above

---

## GET /api/v1/reporting/transactions

Retrieve a paginated list of transactions with optional filters.

**Required role**: `MERCHANT` (own transactions only), `ADMIN`, `SUPPORT`

**Merchant scoping**: For MERCHANT role, the gateway enforces `merchantId` equals the token's `merchantId` claim regardless of any client-supplied `merchantId` query parameter.

### Query Parameters

Same as the underlying reporting-service contract. See `specs/006-reporting-service-api/contracts/api.md` for full parameter list.

| Parameter | Type | Required | Default |
|-----------|------|----------|---------|
| `from` | ISO-8601 datetime | No | now - 30 days |
| `to` | ISO-8601 datetime | No | now |
| `status` | String | No | — |
| `page` | int | No | 0 |
| `size` | int | No | 20 |

**Note**: For MERCHANT role, `cardToken` and `merchantId` query parameters are ignored if supplied — the gateway substitutes values from the JWT claims.

### Response — 200 OK (see reporting-service contract for full response shape)

### Response — 401/403/429/503 — see common responses above

---

## GET /api/v1/reporting/merchants/{merchantId}/summary

Retrieve aggregated transaction summary for a specific merchant.

**Required role**: `MERCHANT` (own merchant ID only), `ADMIN`, `SUPPORT`

**Merchant scoping**: For MERCHANT role, the `merchantId` path variable MUST match the JWT's `merchantId` claim. If it doesn't, the gateway returns 403 before forwarding the request.

### Query Parameters

| Parameter | Type | Required |
|-----------|------|----------|
| `from` | ISO-8601 date (yyyy-MM-dd) | No |
| `to` | ISO-8601 date (yyyy-MM-dd) | No |

### Response — 200 OK (see reporting-service contract for full response shape)

---

## GET /api/v1/reporting/merchants/summary

Retrieve aggregated transaction summaries for all merchants.

**Required role**: `ADMIN`, `SUPPORT` only (MERCHANT role → 403)

### Query Parameters

| Parameter | Type | Required |
|-----------|------|----------|
| `from` | ISO-8601 date (yyyy-MM-dd) | No |
| `to` | ISO-8601 date (yyyy-MM-dd) | No |
| `page` | int | No |
| `size` | int | No |

### Response — 200 OK (see reporting-service contract for full response shape)

---

## GET /api/v1/reporting/settlements/{batchId}

Retrieve details of a settlement batch.

**Required role**: `ADMIN`, `SUPPORT` (MERCHANT role → 403)

### Response — 200 OK (see reporting-service contract for full response shape)

---

## GET /api/v1/reporting/settlements/{batchId}/merchants/{merchantId}

Retrieve a specific merchant's settlement data within a batch.

**Required role**: `ADMIN`, `SUPPORT` (MERCHANT role → 403)

### Response — 200 OK (see reporting-service contract for full response shape)

---

## GET /actuator/health

Health check endpoint. Publicly accessible (no auth required).

### Response — 200 OK

```json
{
  "status": "UP",
  "components": {
    "redis": { "status": "UP" },
    "transactionServiceCircuitBreaker": { "status": "UP", "details": { "state": "CLOSED" } },
    "reportingServiceCircuitBreaker": { "status": "UP", "details": { "state": "CLOSED" } }
  }
}
```

### Response — 503 Service Unavailable (when Redis or a circuit breaker is in OPEN state)

```json
{
  "status": "DOWN",
  "components": {
    "redis": { "status": "DOWN" }
  }
}
```
