# API Contract: Booking Service

All endpoints below are reachable only through `api-gateway`, which requires a valid
JWT with role `FRONT_DESK` or `ADMIN` (FR-017) and applies the same rate limiting as the
platform's other routes. There is **no** DELETE/cancel endpoint in this version
(self-service cancellation was removed per clarification).

## `GET /api/v1/rooms`

Search room availability.

**Query params**: `checkIn` (date, required), `checkOut` (date, required, strictly after
`checkIn` — FR-003)

**Response `200`**:
```json
[
  { "roomId": "uuid", "roomNumber": "101", "roomType": "STANDARD", "nightlyRate": 129.00 }
]
```
Only rooms with no HELD/CONFIRMED booking overlapping the given range are returned
(FR-002).

**Response `400`**: invalid date range (`checkOut <= checkIn`).

## `POST /api/v1/bookings`

Staff submits a booking on a guest's behalf (FR-004).

**Request**:
```json
{
  "roomId": "uuid",
  "checkIn": "2026-11-01",
  "checkOut": "2026-11-03",
  "guestName": "Jane Doe",
  "card": {
    "cardNumber": "4111111111111111",
    "expiry": "12/28",
    "cvv": "123",
    "cardholderName": "Jane Doe"
  }
}
```
`card` is forwarded to `transaction-service` via gRPC and is never persisted or logged
by booking-service (FR-008).

**Response `201`**:
```json
{ "bookingId": "uuid", "status": "HELD" }
```
The booking is HELD immediately, before any payment outcome is known (FR-006); it is
driven to `CONFIRMED`/`CANCELLED` asynchronously by the Kafka consumer described in
`events.md`.

**Response `404`**: room does not exist (FR-005).

**Response `409`**: room is already HELD/CONFIRMED for an overlapping night
(FR-007) — the unique-constraint violation described in `data-model.md`.

**Response `400`**: invalid date range.

## `GET /api/v1/bookings/{id}`

Retrieve a booking's current status and details (FR-013).

**Response `200`**:
```json
{
  "bookingId": "uuid",
  "roomId": "uuid",
  "guestName": "Jane Doe",
  "checkIn": "2026-11-01",
  "checkOut": "2026-11-03",
  "status": "CONFIRMED",
  "cancellationReason": null,
  "transactionId": "txn-uuid"
}
```
`cancellationReason` is populated only when `status = CANCELLED` (one of
`PAYMENT_DECLINED`, `PAYMENT_FAILED`, `HOLD_EXPIRED`, `PAYMENT_AFTER_EXPIRY`).

**Response `404`**: no booking with that id.
