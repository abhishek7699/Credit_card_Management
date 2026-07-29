# REST API Contract: Reporting Service

**Base path**: `/internal/reporting`
**Content-Type**: `application/json`
**Auth**: Network-policy protected (no application-layer auth in v1)
**Methods**: GET only — all other methods return 405 Method Not Allowed

---

## GET /internal/reporting/transactions

Retrieve a paginated list of transactions with optional filtering.

### Query Parameters

| Parameter | Type | Required | Default | Constraints |
|-----------|------|----------|---------|-------------|
| `cardToken` | String | No | — | Exact match filter |
| `merchantId` | String | No | — | Exact match filter |
| `from` | ISO-8601 datetime | No | now - 30 days | Must be before `to` |
| `to` | ISO-8601 datetime | No | now | Must be after `from` |
| `status` | String | No | — | One of: AUTHORIZED, SETTLED |
| `page` | int | No | 0 | ≥ 0 |
| `size` | int | No | 20 | 1–100 |

**Constraint**: `to - from` must not exceed 90 days. Returns 400 if exceeded.

### Response — 200 OK

```json
{
  "content": [
    {
      "transactionId": "550e8400-e29b-41d4-a716-446655440000",
      "cardToken": "tok-abc123",
      "maskedCardNumber": "4111-****-****-1111",
      "merchantId": "merchant-001",
      "amount": "100.00",
      "currencyCode": "USD",
      "status": "SETTLED",
      "batchId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
      "settlementFee": "2.00",
      "netAmount": "98.00",
      "authorizedAt": "2026-06-13T10:00:00Z",
      "settledAt": "2026-06-13T23:59:47Z"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

### Response — 400 Bad Request (invalid filters)

```json
{
  "error": "Date range exceeds maximum of 90 days"
}
```

```json
{
  "errors": [
    { "field": "status", "message": "must be one of: AUTHORIZED, SETTLED" },
    { "field": "size", "message": "must be between 1 and 100" }
  ]
}
```

---

## GET /internal/reporting/merchants/{merchantId}/summary

Retrieve aggregated transaction summary for a specific merchant across all settlement batches, optionally filtered by date range.

### Path Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `merchantId` | String | Merchant identifier |

### Query Parameters

| Parameter | Type | Required | Default | Constraints |
|-----------|------|----------|---------|-------------|
| `from` | ISO-8601 date | No | 30 days ago | Must be before `to` |
| `to` | ISO-8601 date | No | today | Must be after `from` |

### Response — 200 OK

```json
{
  "merchantId": "merchant-001",
  "transactionCount": 42,
  "grossAmount": "4200.00",
  "totalInterchangeFee": "84.00",
  "netSettlementAmount": "4116.00",
  "currencyCode": "USD",
  "batchCount": 3,
  "from": "2026-05-14",
  "to": "2026-06-13"
}
```

### Response — 200 OK (merchant with no transactions in range)

```json
{
  "merchantId": "merchant-new",
  "transactionCount": 0,
  "grossAmount": "0.00",
  "totalInterchangeFee": "0.00",
  "netSettlementAmount": "0.00",
  "currencyCode": "USD",
  "batchCount": 0,
  "from": "2026-05-14",
  "to": "2026-06-13"
}
```

---

## GET /internal/reporting/merchants/summary

Retrieve aggregated summaries for all merchants, optionally filtered by date range. Returns a list ordered by grossAmount descending.

### Query Parameters

| Parameter | Type | Required | Default | Constraints |
|-----------|------|----------|---------|-------------|
| `from` | ISO-8601 date | No | 30 days ago | Must be before `to` |
| `to` | ISO-8601 date | No | today | Must be after `from` |
| `page` | int | No | 0 | ≥ 0 |
| `size` | int | No | 20 | 1–100 |

### Response — 200 OK

```json
{
  "content": [
    {
      "merchantId": "merchant-001",
      "transactionCount": 42,
      "grossAmount": "4200.00",
      "totalInterchangeFee": "84.00",
      "netSettlementAmount": "4116.00",
      "currencyCode": "USD",
      "batchCount": 3,
      "from": "2026-05-14",
      "to": "2026-06-13"
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

---

## GET /internal/reporting/settlements/{batchId}

Retrieve the summary of a specific settlement batch run.

### Path Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `batchId` | String | Settlement batch identifier |

### Response — 200 OK

```json
{
  "batchId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "status": "COMPLETED",
  "triggeredBy": "SCHEDULER",
  "totalTransactionsProcessed": 1250,
  "totalGrossAmount": "312500.00",
  "totalInterchangeFees": "6250.00",
  "totalNetAmount": "306250.00",
  "startedAt": "2026-06-13T23:59:00Z",
  "completedAt": "2026-06-13T23:59:47Z",
  "failureReason": null
}
```

### Response — 404 Not Found

```json
{
  "error": "Settlement batch not found: f47ac10b-58cc-4372-a567-0e02b2c3d479"
}
```

---

## GET /internal/reporting/settlements/{batchId}/merchants/{merchantId}

Retrieve the per-merchant settlement detail within a specific batch.

### Path Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `batchId` | String | Settlement batch identifier |
| `merchantId` | String | Merchant identifier |

### Response — 200 OK

```json
{
  "id": "a3f1c820-b123-4e56-a789-0e02b2c3d479",
  "batchId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "merchantId": "merchant-001",
  "transactionCount": 42,
  "grossAmount": "4200.00",
  "totalInterchangeFee": "84.00",
  "netSettlementAmount": "4116.00",
  "currencyCode": "USD",
  "settlementDate": "2026-06-13"
}
```

### Response — 404 Not Found

```json
{
  "error": "No settlement found for merchantId=merchant-001 in batchId=f47ac10b-58cc-4372-a567-0e02b2c3d479"
}
```

---

## Error Response Format

All error responses follow one of two structures:

**Single error** (404, 400 for range violations):
```json
{ "error": "<human-readable message>" }
```

**Validation errors** (400 for parameter validation):
```json
{
  "errors": [
    { "field": "<field name>", "message": "<validation message>" }
  ]
}
```

---

## Actuator Endpoints (Management Port 8091)

| Endpoint | Description |
|----------|-------------|
| `GET /actuator/health` | Liveness + readiness probes |
| `GET /actuator/health/liveness` | Liveness probe |
| `GET /actuator/health/readiness` | Readiness probe |
| `GET /actuator/info` | Service metadata |
| `GET /actuator/metrics` | Micrometer metrics |
| `GET /actuator/prometheus` | Prometheus scrape endpoint |
