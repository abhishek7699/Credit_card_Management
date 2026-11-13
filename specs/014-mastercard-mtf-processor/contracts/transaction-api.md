# Contract: transaction submission with a payment session

## Public REST (api-gateway, existing route kept)

`POST /api/v1/transactions` — roles MERCHANT or ADMIN (unchanged).

Existing body (simulated path, unchanged):

```json
{"cardNumber": "...", "cardExpiry": "MM/YY", "cvv": "...", "cardholderName": "...", "amount": "99.99", "currencyCode": "USD"}
```

New body (session path):

```json
{"paymentSessionId": "<session id from Mastercard>", "amount": "99.99", "currencyCode": "USD"}
```

Rules:

- Exactly one of `cardNumber` or `paymentSessionId` is allowed. Both, or card fields with a
  session ID, is rejected `400` before anything is forwarded.
- Session path: no card fields are accepted, so none reach the backend.
- Response unchanged: `201` with `{"transactionId": "...", "status": "PENDING"}`.
- Errors unchanged in shape; new `400` message for a bad combination.

## gRPC (`proto/v1/transaction_service.proto`) — additive

```proto
message InitiateTransactionRequest {
  // ... existing fields 1..N unchanged ...
  // payment_session_id: Mastercard Hosted Session ID standing for card details already
  // collected. Set only on the session path; the card fields above must then be empty.
  // Not persisted; forwarded on transaction.initiated only.
  string payment_session_id = 10;
}
```

Field number is new and additive, so no version bump is needed (Principle VII). The
generated stubs are not edited by hand. Contract tests against the generated stub come before
the service change (Principle VII, Principle II).

## Kafka (`transaction.initiated`) — additive

Payload gains optional `paymentSessionId` (string, nullable).
`cardToken` is set on the simulator path and is null on the session path.

Envelope fields unchanged: `eventId`, `eventType`, `sourceService`, `traceId`, `occurredAt`.

## Kafka (`transaction.status.updated`) — additive

`AuthorizationResultEvent` payload gains optional `processor` and `reasonCode`. Existing
consumers ignore unknown fields. Booking's confirmation logic is unchanged in phase 2 and
3; the booking-side race from spec 013 remains a separate decision.
