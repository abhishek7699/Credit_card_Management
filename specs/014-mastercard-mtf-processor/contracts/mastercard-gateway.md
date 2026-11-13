# Contract: Mastercard Gateway operations used (external REST)

Base: `${MASTERCARD_BASE_URL}`, version `${mastercard.api-version}` (provisional `100`).
Auth: HTTP Basic, user `merchant.${MASTERCARD_MERCHANT_ID}`, password `${MASTERCARD_API_PASSWORD}`.
Every path below is built from the single version config value.

Identifiers: `order.id` = `transaction.id` = our `transactionId` (research R3).

| Operation | Method and path | `apiOperation` | Key request fields | Used in |
|---|---|---|---|---|
| Authorize (session) | `PUT /api/rest/version/{v}/merchant/{m}/order/{tx}/transaction/{tx}` | `AUTHORIZE` | `order.amount`, `order.currency`, `sourceOfFunds.session.id` (= paymentSessionId) | phase 2 |
| Retrieve transaction (status lookup) | `GET /api/rest/version/{v}/merchant/{m}/order/{tx}/transaction/{tx}` | — | — | phase 2 (path to confirm, research R4) |
| Void | `PUT /api/rest/version/{v}/merchant/{m}/order/{tx}/transaction/{tx}` | `VOID` | `transaction.targetTransactionId` (required) | phase 2 |
| Capture | `PUT /api/rest/version/{v}/merchant/{m}/order/{tx}/transaction/{tx}` | `CAPTURE` | `order.amount`, `transaction.amount`, `transaction.currency` (fields to confirm, research R10) | phase 4 |

Responses are mapped as follows. The mapping is the only place Mastercard codes are
interpreted.

| Response | Mapped to |
|---|---|
| `result` = `SUCCESS` and the operation's expected outcome | `APPROVED` / `CAPTURED` / `RELEASED` |
| `result` = `FAILURE` / `response.gatewayCode` declines | `DECLINED` / `CAPTURE_REJECTED` / `RELEASE_REJECTED` with `reasonCode` = `response.gatewayCode` |
| HTTP 5xx, timeout, connection error, unparseable body, `result` = `UNKNOWN`, `PENDING` | `UNKNOWN` (caller runs `lookup`) |
| Duplicate transaction identifier error | `UNKNOWN` (caller runs `lookup`; never re-sends AUTHORIZE) |

## Logging rules

- Log `transactionId`, the operation name, `result`, and `reasonCode`.
- Never log request bodies, the session ID in full, `sourceOfFunds`, or any authorization header.
- Log HTTP status and latency at INFO. Log response bodies only at DEBUG after masking
  (not enabled in CI or e2e).

## Not in scope

Refund, partial void, reversal, 3-D Secure, standalone capture, and any Create/Update Session
call from the backend (test-only tooling, research R2).
