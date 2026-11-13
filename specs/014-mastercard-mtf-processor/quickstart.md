# Quickstart: 014 Mastercard processor

## Default (simulator) — unchanged

```sh
docker compose --profile dev up -d --build
scripts/e2e-test.sh
```

Nothing to set. `payment.processor` defaults to `simulated`.

## Phase 2 — Mastercard path against WireMock (no credentials)

```sh
./gradlew :services:authorization-service:test --tests '*MastercardGatewayProcessor*'
```

Stubs are the Version 100 request and response shapes in `contracts/mastercard-gateway.md`.
No network access to Mastercard is made.

## Phase 3 — live MTF test environment (opt-in, never in CI)

Set credentials in the environment only. Do not put them in `.env`, `docker-compose.yml`, or any committed file.

```sh
export MASTERCARD_BASE_URL="https://test-gateway.mastercard.com"   # confirm host for your merchant
export MASTERCARD_MERCHANT_ID="TEST<your id>"
export MASTERCARD_API_PASSWORD="<from the MTF portal>"
export PAYMENT_PROCESSOR=mastercard
docker compose --profile dev up -d --build authorization-service
```

Step 0, before any transaction: confirm the version. Create a session on the MTF host at
version 100:

```sh
curl -s -u "merchant.$MASTERCARD_MERCHANT_ID:$MASTERCARD_API_PASSWORD" -X POST \
  -H 'Content-Type: application/json' \
  "$MASTERCARD_BASE_URL/api/rest/version/100/merchant/$MASTERCARD_MERCHANT_ID/session"
```

`result` must be `SUCCESS`. If not, change `mastercard.api-version` (one value) and recheck.

Then fill the session with a test card (test tooling only, research R2) and submit through
the session path:

```sh
curl -s -X POST http://localhost:8087/api/v1/transactions \
  -H "Authorization: Bearer $MERCHANT_TOKEN" -H 'Content-Type: application/json' \
  -d '{"paymentSessionId":"<session id>","amount":"10.00","currencyCode":"USD"}'
```

### Filling a session from Postman (approved, MTF testing only)

Owner-approved test-tool step. It is for MTF testing with test cards only. Never add it to
backend code, to CI, or to any environment that handles real cards. The card data goes from
Postman directly to Mastercard, never through our services.

Postman request, against the test host, at version 100 (`{v}` = the pinned version):

1. `POST {base}/api/rest/version/{v}/merchant/{merchantId}/session` (Basic auth: user
   `merchant.{merchantId}`, password = API password). Save `session` from the response.
2. `PUT {base}/api/rest/version/{v}/merchant/{merchantId}/session/{sessionId}` with the test PAN
   in `sourceOfFunds.provided.card` (number, expiry, securityCode) and `sourceOfFunds.type`
   set to `CARD`. Use the same version as step 1 (research R2).
3. Use the session ID in the session-path request below.

Do not paste real card numbers into Postman. Use only the provisional test cards.

Test cards (provisional, confirm on the MTF test-cards page first):
- Approve: `5123450000000008` or `2223000000000007`, expiry `01/39`
- Decline: same PAN, expiry `05/39`

## Phase 4 — settlement capture

With `PAYMENT_PROCESSOR=mastercard`, run the settlement batch as today. Each AUTHORIZED
transaction is captured before it is marked SETTLED. Check the transaction rows: `SETTLED`
only after a `CAPTURED` outcome.

## Check nothing leaked

```sh
docker compose logs --no-color | grep -E '[0-9]{13,19}|api[_-]?password|sourceOfFunds' && echo LEAK || echo clean
```

Expected: `clean`.
