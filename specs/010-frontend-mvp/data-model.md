# Phase 1 Data Model: Frontend MVP (Staff Room Board)

This layer introduces no new persisted business entity (per spec's Key Entities
section). This document covers (1) the one additive backend change and (2) the
frontend's own view-model types and client-side state shape.

## 1. Backend — `booking-service` (additive only, extends layer 2b)

### `Room` / `Booking` tables

Unchanged — no migration. The additive fields below are computed at read time from
existing columns (`bookings.status`, `bookings.check_in`, `bookings.guest_name`), the
same pattern layer 2b's `getBoard()` already uses for `guestName`/`checkIn`/`checkOut`.

### `RoomBoardResponse.RoomBoardEntry` *(extended)*

| Field | Type | Present when | Notes |
|---|---|---|---|
| `roomId` | UUID | always | unchanged |
| `roomNumber` | String | always | unchanged |
| `position` | int | always | unchanged |
| `status` | RoomStatus | always | unchanged |
| `guestName` | String | `status == OCCUPIED` | unchanged (2b FR-013) |
| `checkIn` | LocalDate | `status == OCCUPIED` | unchanged |
| `checkOut` | LocalDate | `status == OCCUPIED` | unchanged |
| `currentBookingId` | UUID | `status == OCCUPIED` | **new** — the occupying booking's id, for check-out |
| `arrivingBookingId` | UUID | a `CONFIRMED` booking exists for this room with `checkIn == today` | **new** — for check-in |
| `arrivingGuestName` | String | same as `arrivingBookingId` | **new** — independent of `guestName`; both can be populated at once (same-day turnover) |

### `BookingRepository` *(extended)*

New method, same conditional-query style as the rest of the repository:

```java
List<Booking> findByStatusAndCheckIn(BookingStatus status, LocalDate checkIn);
```

Called once per `getBoard()` invocation with `(CONFIRMED, LocalDate.now())`, mapped to
`roomId` the same way the existing `CHECKED_IN` lookup already is — no per-room query.

### `RoomService.getBoard()` *(extended)*

Gains a second map (`arrivingByRoomId`, built from the query above) alongside the
existing `occupantByRoomId`; `toEntry(...)` reads both maps independently when
building each `RoomBoardEntry`.

## 2. Frontend — view-model types (`frontend/src/types/staff.ts`)

```ts
export type StaffRole = 'FRONT_DESK' | 'HOUSEKEEPING';

export type RoomStatus = 'VACANT_CLEAN' | 'OCCUPIED' | 'DIRTY' | 'MAINTENANCE';

export interface RoomBoardEntry {
  roomId: string;
  roomNumber: string;
  position: number;
  status: RoomStatus;
  guestName: string | null;
  checkIn: string | null;       // ISO date
  checkOut: string | null;      // ISO date
  currentBookingId: string | null;
  arrivingBookingId: string | null;
  arrivingGuestName: string | null;
}

export interface RoomBoardFloor {
  floor: number;
  rooms: RoomBoardEntry[];
}

export type BookingStatus = 'HELD' | 'CONFIRMED' | 'CANCELLED' | 'CHECKED_IN' | 'CHECKED_OUT';

export interface Booking {
  bookingId: string;
  roomId: string;
  guestName: string;
  checkIn: string;
  checkOut: string;
  status: BookingStatus;
  cancellationReason: string | null;
  transactionId: string | null;
}

/** Submitted straight to the API — never constructed into, or read back from, any
 *  persisted Redux state or storage (FR-013). */
export interface BookingFormValues {
  roomId: string;
  guestName: string;
  checkIn: string;
  checkOut: string;
  card: {
    cardNumber: string;
    expiry: string;
    cvv: string;
    cardholderName: string;
  };
}
```

## 3. Frontend — client-side state

### `staffSessionSlice` (new Redux slice, `features/staff-session/staffSessionSlice.ts`)

```ts
interface StaffSessionState {
  role: StaffRole | null;
  token: string | null;
  status: 'anonymous' | 'authenticated' | 'expired';
}
```

- `login` (role, token) → `status: 'authenticated'`, persisted to `sessionStorage`.
- `sessionExpired` → `status: 'expired'`, token cleared, `sessionStorage` cleared.
  (`StaffAuthGate` treats `'anonymous'` and `'expired'` the same — both show
  `LoginScreen` — but `'expired'` exists as a distinct value so a future iteration
  could show a "your session ended" message; out of scope to add that copy now.)
- `logout` → back to `'anonymous'`, same clearing as `sessionExpired`.
- Hydrated once at app start by reading `sessionStorage` (if a role+token pair is
  present, start in `'authenticated'`; otherwise `'anonymous'`).

This slice is entirely separate from the existing `authSlice` (which remains the PIN
flow's own state, untouched).

### Not modeled as persisted state — explicitly ephemeral, per FR-013

Card field values (`BookingFormValues.card`) live only in `BookingForm`'s local
`useState`, for exactly as long as the form is on screen; they are never dispatched
into Redux, never written to any storage, and the form's values are discarded (not
merely hidden) once submission resolves either way.
