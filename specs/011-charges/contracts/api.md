# API Contract: Charges (layer 2c)

All endpoints below are reachable only through `api-gateway`, same as every prior
layer.

## `POST /api/v1/bookings/{id}/charges`

Front-desk only (FR-001; matches the existing broad `/api/v1/bookings/**` rule —
no new gateway matcher needed for this one).

**Request**:
```json
{ "type": "ROOM_SERVICE", "description": "Club sandwich + fries", "amount": 18.50 }
```
`type` must be one of `ROOM_SERVICE`, `MINIBAR`, `LAUNDRY`, `OTHER`.

**Response `201`**:
```json
{ "chargeId": "uuid", "type": "ROOM_SERVICE", "description": "Club sandwich + fries",
  "amount": 18.50, "createdAt": "2026-10-30T18:04:11Z" }
```

**Response `404`**: no booking with that id.

**Response `409`**: the booking is not `CHECKED_IN` (FR-002) — nothing recorded.

## `GET /api/v1/bookings/{id}/charges`

Front-desk **or** housekeeping (FR-003) — a new gateway matcher, same pattern as
the room board's, placed before the broad rule.

**Response `200`**:
```json
[
  { "chargeId": "uuid", "type": "ROOM_SERVICE", "description": "Club sandwich + fries",
    "amount": 18.50, "createdAt": "2026-10-30T18:04:11Z" },
  { "chargeId": "uuid", "type": "MINIBAR", "description": "Two sodas",
    "amount": 6.00, "createdAt": "2026-10-30T19:40:02Z" }
]
```
Ordered by `createdAt` ascending (FR-003).

## `GET /api/v1/bookings/{id}` *(layer 2a, response extended — additive only)*

Front-desk or housekeeping (new matcher, same reasoning as the `/charges` one
above — housekeeping needs this to see the balance from the same screen as the
charges list, FR-014). Unchanged existing fields keep their exact meaning; two new
fields are appended.

**Response `200`**:
```json
{
  "bookingId": "uuid", "roomId": "uuid", "guestName": "Jane Doe",
  "checkIn": "2026-12-01", "checkOut": "2026-12-03",
  "status": "CHECKED_IN", "cancellationReason": null, "transactionId": "txn-uuid",
  "charges": [ /* same shape as GET .../charges above */ ],
  "balanceDue": 24.50
}
```

## `POST /api/v1/bookings/{id}/check-out` *(layer 2b, request/response extended)*

Front-desk only, unchanged gateway wiring. The request body gains one optional
field; the response adds the same `charges`/`balanceDue` fields as booking detail.

**Request (balance is zero)** — no body needed, or an empty object:
```json
{}
```

**Request (balance is greater than zero)**:
```json
{ "card": { "cardNumber": "4111111111111111", "expiry": "12/30", "cvv": "123",
            "cardholderName": "Jane Doe" } }
```

**Response `200` (zero balance — completes immediately, FR-013)**:
```json
{ "bookingId": "uuid", "...": "...", "status": "CHECKED_OUT", "charges": [],
  "balanceDue": 0.00 }
```

**Response `200` (balance charged — still pending, FR-007)**: same shape, with
`"status": "CHECKOUT_PENDING"` and `balanceDue` unchanged — the room stays
`OCCUPIED` until a later poll of `GET /api/v1/bookings/{id}` shows `CHECKED_OUT`
(FR-010) or `CHECKED_IN` again (FR-011/FR-012a), the same polling pattern layer 2d
already uses for the original booking-confirmation saga.

**Response `400`**: balance is greater than zero and no `card` was supplied.

**Response `409`**: the booking is not `CHECKED_IN` — includes the case where a
previous checkout payment attempt for it is still pending (FR-009).

## `GET /api/v1/rooms/board` *(layer 2b/2d, response extended — additive only)*

Front-desk or housekeeping, unchanged gateway wiring. One new field per room entry.

**Response `200`** (relevant excerpt):
```json
{ "roomId": "uuid", "roomNumber": "101", "status": "OCCUPIED",
  "guestName": "Jane Doe", "currentBookingId": "uuid",
  "hasBalanceDue": true }
```
`hasBalanceDue` is present (`true`/`false`) only when `status == "OCCUPIED"`
(FR-006); absent otherwise, same presence rule as `guestName`.

## Error handling (all of the above, via `api-gateway`)

| Status | Meaning |
|---|---|
| `400` | Checkout attempted with a balance due and no card supplied. |
| `404` | No booking (or no room, for the board) with that id. |
| `409` | Charge/checkout attempted against a booking in the wrong state — not `CHECKED_IN` for a charge; not `CHECKED_IN` or already mid-checkout for a checkout attempt. |
