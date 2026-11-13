# Research: Mastercard Gateway test environment as payment processor (014)

Each entry: decision, rationale, alternatives. "Verified" means read on an official
Mastercard page during planning; "Unverified" means it must be confirmed before the phase
that depends on it.

## R1 — Mastercard API version: pin `100`, from one config value

- **Decision**: `mastercard.api-version: 100` in a single config property. Every Mastercard
  request path is built from it. No code change is needed to move versions.
- **Rationale**: The official Version 100 operation pages exist for Authorize, Capture and
  Void (`/api/rest/version/100/merchant/{merchantId}/order/{orderId}/transaction/{transactionId}`),
  and Mastercard's migration guide describes Version 100 for the Direct Payment API. The
  owner asked not to use version 12 and to match the official Postman collection, which the
  search results show using version 100.
- **Status**: Provisional. The owner asked for confirmation by the test merchant account. That
  check runs as the first step of phase 3: a Create Session call on the MTF host must return
  `result: SUCCESS` at version 100. If it does not, the version changes in config and the
  decision is revisited.
- **Alternatives**: Version 12 (rejected by owner, older); version 58, 52 (seen in regional
  docs, older); `latest` (rejected: an unpinned version can change under us).
- **Unverified**: The Postman collection's own version string (the collection download was not
  retrievable); the test merchant's enabled version.

## R2 — Card data path: Hosted Session, backend receives only a session ID

- **Decision**: The browser (or a test tool acting for the cardholder) sends card details to
  Mastercard to create and fill a payment session. The backend receives only the session ID.
- **Rationale**: Matches the spec's decision, and keeps raw card data out of the backend
  (constitution Principle I). The proto's `card_number`, `card_expiry` and `cvv` fields
  remain for the simulated path only.
- **Verified**: Create Session is `POST .../merchant/{merchantId}/session`, authenticated with
  Basic auth (user `merchant.<id>`, API password), and returns a `session` string with a
  `result` of SUCCESS, PENDING, FAILURE or UNKNOWN. Update Session can carry card data in
  `sourceOfFunds.provided.card`.
- **Open risk**: The owner's Postman test would fill a session server-side with a test PAN
  (Update Session). That sends card data from the tester's tool to Mastercard, not through
  our backend, so it does not break Principle I's rule about the system. It must be kept out
  of the backend and out of any CI job. It is the closest alternative to a browser form and
  is allowed for test cards only. Flagged for review in plan.md.
- **Unverified**: Whether Mastercard's documentation restricts server-side population of a
  session. The reference pages show the field but do not describe a PCI restriction either way.
- **Alternatives**: Direct Payment with card fields in AUTHORIZE (rejected: CVV is discarded
  upstream and the card would have to flow through Kafka); Hosted Payment Page redirect
  (rejected: changes the async flow the spec keeps).

## R3 — Identifiers: transactionId is the order and transaction id at Mastercard

- **Decision**: Use our `transactionId` as Mastercard's `order.id` and `transaction.id` for
  AUTHORIZE, CAPTURE and VOID.
- **Rationale**: A retried request carries the same identifiers. Mastercard rejects a
  repeated transaction identifier on the same order, so a retry cannot authorize or capture
  twice. Retries then go to status lookup, not to a second AUTHORIZE.
- **Unverified**: The exact error code Mastercard returns for a duplicate transaction
  identifier. Phase 2 tests the handling against WireMock using a documented response, and
  phase 3 confirms it on MTF.
- **Alternatives**: A new random id per attempt (rejected: allows double authorization).

## R4 — Unknown results: query Mastercard before acting

- **Decision**: On timeout, connection error, malformed response, or a `result` of UNKNOWN,
  the processor performs a status lookup for the same order and transaction identifiers. The
  transaction is set from that lookup's answer. If the lookup is also unknown, the transaction
  stays PENDING and is reported. It is never marked DECLINED on an unknown result alone.
- **Rationale**: Spec FR-005 and SC-002. A timed-out AUTHORIZE can still have been approved.
- **Unverified**: The Mastercard status lookup operation to use (expected: Retrieve
  Transaction on the same order/transaction path, `GET`). Confirm its path and response
  fields on the version 100 page in phase 2.
- **Alternatives**: Mark DECLINED on timeout (rejected: double-count risk); retry AUTHORIZE
  blindly (rejected: spec forbids double authorization).

## R5 — Simulator stays the default; processor selected by configuration

- **Decision**: `payment.processor` property, values `simulated` (default) or
  `mastercard`. Selection happens once at startup through a Spring configuration bean.
- **Rationale**: The e2e script and CI already run the simulator. An unset or unknown value
  must fall back to `simulated` only when unset; an unknown value fails startup (never a
  silent fallback, per spec edge cases).
- **Alternatives**: Feature flag per request (rejected: adds per-request branching and risk);
  separate deployable (rejected: out of scope).

## R6 — Releasing a hold: `releaseHold` on the processor interface

- **Decision**: `PaymentProcessor.releaseHold(transactionId, cardToken)`. The timeout marker and
  duplicate check stay in the service layer (see contracts/payment-processor.md).
  - `SimulatedProcessor`: adds the amount back to `card_accounts.available_credit` (the
    behavior spec 013 specifies).
  - `MastercardGatewayProcessor`: calls VOID with `transaction.targetTransactionId` set to the
    authorization's transaction id.
- **Rationale**: Owner decision. This is the only void in scope. A VOID is valid only before
  capture, which holds because 013's timeout applies only to PENDING transactions and CAPTURE
  happens at settlement (phase 4).
- **Verified**: VOID is `PUT .../transaction/{transactionId}` with `apiOperation: VOID` and
  `transaction.targetTransactionId`.
- **Edge**: A VOID for an authorization that is already captured is rejected by Mastercard.
  The processor maps that rejection to `RELEASE_REJECTED` and the caller records it. It does
  not fall back to a refund (refunds are out of scope).
- **Dependency (satisfied)**: Spec 013 is merged to `origin/main` (PR #14). Its
  `CreditReleaseService` compensation moves behind this operation in phase 1. See plan.md, Gate G0.

## R7 — Test cards (provisional)

- **Decision**: Test PANs `5123450000000008` and `2223000000000007`. Expiry `01/39` approves
  and `05/39` declines. Both are marked provisional and must be confirmed on the MTF
  merchant portal's test-cards page before phase 3.
- **Rationale**: Owner-provided values. Partially corroborated by search excerpts (01/39 approve,
  05/39 decline on MTF hosts) during specification.
- **Unverified**: Amount-based outcome rules for MTF (none found). CVV behavior beyond 102 →
  NO_MATCH.

## R8 — Credentials: environment variables only, never logged

- **Decision**: `MASTERCARD_MERCHANT_ID` (must carry the `TEST` prefix on MTF),
  `MASTERCARD_API_PASSWORD`, `MASTERCARD_BASE_URL`. Read at startup into a configuration
  record whose `toString()` masks the password. Not committed (`.env` stays gitignored).
- **Rationale**: Constitution Security & Compliance (secrets via environment variables;
  never in source). Masking prevents accidental logs.
- **Alternatives**: Vault (already in the stack for card tokens, but not needed for test
  credentials; revisit for production under GKE, out of scope).

## R9 — Testing against Mastercard: WireMock in phase 2, live MTF in phase 3

- **Decision**: Phase 2 tests use WireMock stubs of the Version 100 request and response
  shapes. Phase 3 adds a separate, opt-in test profile that calls MTF and is never run in CI.
- **Rationale**: CI has no credentials (spec FR-012). The opt-in profile keeps live calls out of
  the default build.
- **Alternatives**: Recorded fixtures only (rejected: no proof the shapes match today's API);
  a Mastercard sandbox mock service (not available for this account).

## R10 — Settlement capture (phase 4)

- **Decision**: When `payment.processor=mastercard`, the settlement batch calls CAPTURE with
  `order.id` and `transaction.id` set to our transactionId, before writing SETTLED. A failed
  or unknown capture leaves the row AUTHORIZED and triggers the same status lookup as R4.
  Hook point: `SettlementItemProcessor.process`. Rows arrive as AUTHORIZED only through
  `transaction.authorized` from transaction-service (013 option Y).
- **Caller**: settlement-service cannot call authorization-service's processor, so capture needs
  its own small client there (plan.md, open decision 1).
- **Rationale**: Spec FR-009/FR-010.
- **Unverified**: The Capture request fields for a gateway-initiated authorization (the docs
  describe a separate standalone-capture form, which does not apply here).

## R11 — Session path through the public API

- **Decision**: `POST /api/v1/transactions` accepts either the existing card body or
  `{"paymentSessionId": "...", "amount", "currencyCode", "merchantId"?}`. The proto gains an
  additive field `payment_session_id` on `InitiateTransactionRequest`. The request with a
  session must not contain card fields; if it does, the service rejects it.
- **Rationale**: Spec FR-006. The proto change is additive, so it is not breaking under
  Principle VII. Contract tests come first.
- **Alternatives**: A separate endpoint (rejected: duplicates the role and rate-limit rules).
