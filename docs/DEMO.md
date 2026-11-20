# Local demo

One guest booking, followed through the whole system. Needs Docker, Java 17, Node 20. Takes ~10 minutes.

## 1. Start
```bash
cp .env.example .env     # then add the 4 MASTERCARD_DEV_* values (sandbox keys, .p12 outside the repo): docs/CARD_VALIDATION.md
./gradlew assemble -x test
docker compose -f docker-compose.yml -f docker-compose.card-validation-dev.yml --profile dev up -d --build
until curl -sf localhost:8088/actuator/health >/dev/null && curl -sf localhost:8092/actuator/health >/dev/null; do sleep 3; done
cd frontend && npm install && npm run dev      # leave running, new terminal for the rest
```
`--profile dev` adds Zipkin and seeds the test card `4111111111111111` the payment core will approve. Then let the
payment email find a recipient (one-off):
```bash
docker compose exec -T cockroachdb cockroach sql --insecure -e "INSERT INTO notification_service.cardholder_contacts (card_token, masked_card_number, email, phone_number, transaction_notifications_enabled) SELECT card_token, '****-****-****-1111', 'guest@example.com', '+15555550123', true FROM authorization_service.card_accounts ON CONFLICT (card_token) DO NOTHING;"
```

## 2. Guest books (no sign-in)
Open http://localhost:5173. Pick check-in **today**, check-out tomorrow, Search, Book a room, fill name and phone, then the
Mastercard sandbox card: `5102589999999913`, `10/50`, CVV `123`, name `Jane Candy-Smith`. Confirm booking.
The card is checked with Mastercard Account Validation and **not charged**; only the verdict, reference and last 4 are kept.
```bash
docker compose logs transaction-service | grep "Card validation"   # MATCHED referenceId=AVM_...
```

## 3. Staff handles the stay
Open http://localhost:5173/staff, choose **Front Desk**, Sign in. The room shows an **ONLINE** badge.
1. Click the room, **Check In** (if refused because the room is Dirty, book another room).
2. **Add Charge**: e.g. Minibar, 42.50.
3. **Charge and Check Out** with the seeded card `4111111111111111`, `12/30`, CVV `123`, any name. The room turns Dirty.
   (The guest's sandbox card is only for the check; payment here goes through the built-in simulator.)

## 4. Email and trace
- Mailpit http://localhost:8025: a **Payment Approved** email to guest@example.com (a few seconds).
- Zipkin http://localhost:9411: Service `booking-service`, span `http post /api/v1/bookings/{id}/check-out`, Run Query, open
  the newest trace: gRPC to transaction-service, the outbox, Kafka, authorization-service, and the outcome back.

## 5. Settlement and reporting
```bash
docker compose exec -T settlement-service curl -s -X POST localhost:8080/internal/settlement/trigger   # prints a batchId
TOKEN=$(curl -s -X POST localhost:9000/dev/login -H 'Content-Type: application/json' -d '{"role":"ADMIN"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["token"])')
curl -s localhost:8087/api/v1/reporting/settlements/<batchId> -H "Authorization: Bearer $TOKEN"
```
Wait a few seconds after the trigger. The batch shows `COMPLETED`, the totals, and `"acquirerName":"Chase"`.
(Chase is a display label set by `SETTLEMENT_ACQUIRER_NAME`; there is no bank connection.)

## 6. Housekeeping
At http://localhost:5173/staff, Sign out, choose **Housekeeping**, Sign in, click the Dirty room, **Mark Clean**. It is Vacant again.

## Stop
`docker compose -f docker-compose.yml -f docker-compose.card-validation-dev.yml --profile dev down` (add `-v` to wipe data).
Without Mastercard keys, skip the override file: the card check is then off and any well-formed card passes.
