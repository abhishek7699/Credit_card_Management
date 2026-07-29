# REST API Contract: Settlement Service

**Base path**: `/internal/settlement`
**Content-Type**: `application/json`
**Auth**: Network-policy protected (no application-layer auth in v1)

---

## POST /internal/settlement/trigger

Manually trigger the settlement batch job. Creates a new `SettlementBatch` record with `triggered_by=MANUAL`.

### Request

No body required.

```http
POST /internal/settlement/trigger
```

### Response — 202 Accepted

```json
{
  "batchId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "status": "RUNNING",
  "triggeredBy": "MANUAL",
  "startedAt": "2026-06-13T23:59:00.000Z"
}
```

### Response — 409 Conflict

Returned when a batch job is already running.

```json
{
  "error": "Settlement batch is already running",
  "runningBatchId": "f47ac10b-58cc-4372-a567-0e02b2c3d479"
}
```

---

## GET /internal/settlement/reports/{merchantId}/{batchId}

Retrieve the settlement report for a specific merchant and batch run.

### Path Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `merchantId` | `String` | Merchant identifier |
| `batchId` | `String` | Batch run UUID from trigger response |

### Response — 200 OK

```json
{
  "merchantId": "merchant-001",
  "batchId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "transactionCount": 42,
  "grossAmount": "10500.00",
  "totalInterchangeFee": "210.00",
  "netSettlementAmount": "10290.00",
  "currencyCode": "USD",
  "settlementDate": "2026-06-13"
}
```

### Response — 404 Not Found

```json
{
  "error": "No settlement report found for merchantId=merchant-001 and batchId=f47ac10b-58cc-4372-a567-0e02b2c3d479"
}
```

---

## GET /internal/settlement/batches/{batchId}

Retrieve batch execution status and summary.

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
  "startedAt": "2026-06-13T23:59:00.000Z",
  "completedAt": "2026-06-13T23:59:47.321Z"
}
```

### Response — 404 Not Found

```json
{
  "error": "No batch found with batchId=f47ac10b-58cc-4372-a567-0e02b2c3d479"
}
```

---

## Error Response Format

All error responses follow this structure:

```json
{
  "error": "<human-readable message>"
}
```

Validation errors (400):

```json
{
  "errors": [
    {
      "field": "<field name>",
      "message": "<validation message>"
    }
  ]
}
```

---

## Actuator Endpoints (Management Port 8085)

| Endpoint | Description |
|----------|-------------|
| `GET /actuator/health` | Liveness + readiness probes |
| `GET /actuator/health/liveness` | Liveness probe |
| `GET /actuator/health/readiness` | Readiness probe |
| `GET /actuator/info` | Service metadata |
| `GET /actuator/metrics` | Micrometer metrics |
| `GET /actuator/prometheus` | Prometheus scrape endpoint |
