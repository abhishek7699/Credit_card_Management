# API Contract: Charges (layer 2c)

All endpoints below are reachable only through `api-gateway`, same as every prior
layer.

## `POST /api/v1/bookings` *(layer 2a, request/response revised — no card, confirmed immediately)*

Front-desk only, same gateway wiring as before. **No `card` field any more** — a
booking is confirmed the instant this call succeeds, with no payment saga, no
polling. `guestName`, `guestPhone`, `checkIn`, and `checkOut` are required;
everything else is optional (FR-017/FR-018/FR-019, Clarifications:
required-field bundle).

**Request**:
```json
{
  "roomId": "uuid",
  "guestName": "Jane Doe",
  "guestPhone": "+1-555-0100",
  "checkIn": "2026-12-01",
  "checkOut": "2026-12-03",

  "guestEmail": "jane@example.com",
  "visitingFrom": "Chicago, IL",
  "adults": 2,
  "children": 1,
  "expectedArrivalTime": "15:00",
  "expectedDepartureTime": "11:00",
  "notes": "Requested a crib"
}
```
`guestEmail`, `visitingFrom`, `adults`, `children`, `expectedArrivalTime`,
`expectedDepartureTime`, and `notes` may all be omitted. `adults` defaults to `1`
and `children` defaults to `0` when omitted (FR-018).

**Response `201`** — the full booking detail, already `CONFIRMED` (no more thin
"created" response and no more polling for confirmation — FR-020):
```json
{
  "bookingId": "uuid", "roomId": "uuid", "status": "CONFIRMED",
  "cancellationReason": null,
  "guestName": "Jane Doe", "guestPhone": "+1-555-0100",
  "guestEmail": "jane@example.com", "visitingFrom": "Chicago, IL",
  "adults": 2, "children": 1,
  "checkIn": "2026-12-01", "checkOut": "2026-12-03",
  "expectedArrivalTime": "15:00", "expectedDepartureTime": "11:00",
  "notes": "Requested a crib",
  "charges": [], "balanceDue": 240.00
}
```
`balanceDue` already reflects the full room cost (`nights × room.nightlyRate`) —
it is due at checkout, not at booking (FR-021).

**Response `400`**: a required field (`guestName`, `guestPhone`, `checkIn`, or
`checkOut`) is missing, or `checkOut` is not after `checkIn` (FR-019).

**Response `409`**: the room is already booked for an overlapping date range
(the existing room-night unique constraint, unchanged from layer 2a).

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

## `GET /api/v1/bookings/{id}` *(response revised — guest details added, transactionId removed)*

Front-desk or housekeeping (new matcher, same reasoning as the `/charges` one
above — housekeeping needs this to see the balance and guest details from the
same screen as the charges list, FR-014/FR-023).

`transactionId` is **removed** from the response — its only remaining role is
an internal legacy-payment marker (data-model.md), not something staff or the
frontend need to see. `cancellationReason` **stays, unchanged** — always `null`
for a booking this layer's own code creates, but the field and its underlying
`CancellationReason` values are kept for layer 2f's staff-cancellation feature
(research.md §8). The full guest-detail record is **added**, visible whether
the booking is still arriving or already checked in (FR-023, Clarifications:
"applies to both").

**Response `200`**:
```json
{
  "bookingId": "uuid", "roomId": "uuid", "status": "CHECKED_IN",
  "cancellationReason": null,
  "guestName": "Jane Doe", "guestPhone": "+1-555-0100",
  "guestEmail": "jane@example.com", "visitingFrom": "Chicago, IL",
  "adults": 2, "children": 1,
  "checkIn": "2026-12-01", "checkOut": "2026-12-03",
  "expectedArrivalTime": "15:00", "expectedDepartureTime": "11:00",
  "notes": "Requested a crib",
  "charges": [ /* same shape as GET .../charges above */ ],
  "balanceDue": 264.50
}
```

## `POST /api/v1/bookings/{id}/check-out` *(layer 2b, request/response — unchanged this revision)*

Front-desk only, unchanged gateway wiring, unchanged request/response shape.
This is the saga the rework explicitly keeps as-is — the only change is that
`balanceDue` now routinely includes the room cost itself, not just extras, so
the balance-due branch below is reached far more often than before
(Assumptions: "zero-balance checkout is now a narrow edge case").

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
(FR-010) or `CHECKED_IN` again (FR-011/FR-012a).

**Response `400`**: balance is greater than zero and no `card` was supplied.

**Response `409`**: the booking is not `CHECKED_IN` — includes the case where a
previous checkout payment attempt for it is still pending (FR-009).

## `GET /api/v1/rooms/board` *(response extended — guest details added to the arriving/occupied panel)*

Front-desk or housekeeping, unchanged gateway wiring. `hasBalanceDue` is
unchanged from the prior version; the guest-detail fields are added so the
existing room panel can show them without a second request (Clarifications:
"existing panel extended... applies to both arriving and occupied-room panels").

**Response `200`** (relevant excerpt):
```json
{ "roomId": "uuid", "roomNumber": "101", "status": "OCCUPIED",
  "guestName": "Jane Doe", "currentBookingId": "uuid",
  "guestPhone": "+1-555-0100", "guestEmail": "jane@example.com",
  "visitingFrom": "Chicago, IL", "adults": 2, "children": 1,
  "expectedArrivalTime": "15:00", "expectedDepartureTime": "11:00",
  "notes": "Requested a crib",
  "hasBalanceDue": true }
```
`hasBalanceDue` and the guest-detail fields are present only when
`status == "OCCUPIED"` **or** there is an upcoming, not-yet-checked-in booking
for that room today (FR-006/FR-023); absent otherwise, same presence rule as
`guestName`.

## Error handling (all of the above, via `api-gateway`)

| Status | Meaning |
|---|---|
| `400` | Booking creation missing a required field, or with `checkOut` not after `checkIn`; checkout attempted with a balance due and no card supplied. |
| `404` | No booking (or no room, for the board) with that id. |
| `409` | Booking creation with an overlapping room/date range; charge/checkout attempted against a booking in the wrong state — not `CHECKED_IN` for a charge; not `CHECKED_IN` or already mid-checkout for a checkout attempt. |
