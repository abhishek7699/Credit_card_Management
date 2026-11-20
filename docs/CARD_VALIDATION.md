# Guest card validation (pay-later bookings)

A guest booking from the public front office must include a card. booking-service asks transaction-service to
`ValidateCard` **before** confirming; transaction-service calls Mastercard Account Validation
(`libs/mastercard-developers`, OAuth 1.0a signed). Nothing is charged.

- Booking keeps only the verdict, Mastercard's `referenceId` and the last 4 digits
  (`bookings.card_validation_status / card_validation_reference_id / card_last4`).
- The card number and CVC exist only in memory for the call. Nothing stores or logs them
  (`CardDetails.toString()` is masked; `GuestCardValidationFlowIT` scans every table and every log line).
- Not matched: HTTP 422 with a message for the guest. Mastercard unreachable or erroring: HTTP 503, booking not
  confirmed (fail closed). Staff bookings are unchanged and take no card.

## Switch

`card-validation.enabled` on transaction-service. **Off by default** (CI, e2e, default compose): a well-formed
card always passes and no credentials are needed. The `dev` profile turns it on.

## Try it with the Mastercard sandbox

1. `.env` in this checkout has `MASTERCARD_DEV_CONSUMER_KEY`, `MASTERCARD_DEV_KEY_PATH` (a `.p12` outside the
   repo), `MASTERCARD_DEV_KEY_ALIAS`, `MASTERCARD_DEV_KEYSTORE_PASSWORD`.
2. `docker compose -f docker-compose.yml -f docker-compose.card-validation-dev.yml up -d --build`
3. Open the guest front office, search dates, pick a room, and use the sandbox test card:

   | Field | Value |
   |---|---|
   | Card number | `5102589999999913` |
   | Expiry | `10/50` |
   | CVV | `123` |
   | Name on card | `Jane Candy-Smith` |

   The booking confirms. The sandbox picks the scenario from the referenceId prefix, set by
   `CARD_VALIDATION_REFERENCE_PREFIX` (default `AVM_` = matched). To see a rejection, restart transaction-service
   with `CARD_VALIDATION_REFERENCE_PREFIX=AVNOM_` (CVC and name not matched). Other prefixes are in Mastercard's
   Account Validation sandbox testing guide.
