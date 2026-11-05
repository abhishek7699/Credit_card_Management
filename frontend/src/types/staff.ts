export type StaffRole = 'FRONT_DESK' | 'HOUSEKEEPING';

export type RoomStatus = 'VACANT_CLEAN' | 'OCCUPIED' | 'DIRTY' | 'MAINTENANCE';

export interface RoomBoardEntry {
  roomId: string;
  roomNumber: string;
  position: number;
  status: RoomStatus;
  guestName: string | null;
  checkIn: string | null;
  checkOut: string | null;
  currentBookingId: string | null;
  arrivingBookingId: string | null;
  arrivingGuestName: string | null;
  /** null when the room has no current occupant (not CHECKED_IN) — only ever
   *  true/false for an occupied room (US2). */
  hasBalanceDue: boolean | null;
}

export interface RoomBoardFloor {
  floor: number;
  rooms: RoomBoardEntry[];
}

// CONFIRMED is reached synchronously on creation now (2026-10-31 rework) -- 'HELD'
// no longer exists. 'CANCELLED' is kept: unreachable through this layer's own flow
// today, but a valid status layer 2f's planned staff-cancellation feature will reach.
export type BookingStatus =
  | 'CONFIRMED'
  | 'CANCELLED'
  | 'CHECKED_IN'
  | 'CHECKOUT_PENDING'
  | 'CHECKED_OUT';

export type ChargeType = 'ROOM_SERVICE' | 'MINIBAR' | 'LAUNDRY' | 'OTHER';

export interface Charge {
  chargeId: string;
  type: ChargeType;
  description: string;
  amount: number;
  createdAt: string;
}

/** Guest-detail fields recorded at booking (FR-017/FR-018) -- visible on a
 *  booking's own detail both before and after check-in (FR-023). */
export interface GuestDetails {
  guestName: string;
  guestPhone: string;
  guestEmail: string | null;
  visitingFrom: string | null;
  adults: number;
  children: number;
  expectedArrivalTime: string | null;
  expectedDepartureTime: string | null;
  notes: string | null;
}

export interface Booking extends GuestDetails {
  bookingId: string;
  roomId: string;
  checkIn: string;
  checkOut: string;
  status: BookingStatus;
  cancellationReason: string | null;
  charges: Charge[];
  balanceDue: number;
}

export interface CardDetails {
  cardNumber: string;
  expiry: string;
  cvv: string;
  cardholderName: string;
}

/** Submitted straight to the API — no card, of any kind, at this step (FR-017).
 *  guestName/guestPhone/checkIn/checkOut are required; every other field is
 *  optional (FR-018) and simply omitted from the request body when blank. */
export interface BookingFormValues {
  roomId: string;
  guestName: string;
  guestPhone: string;
  checkIn: string;
  checkOut: string;
  guestEmail?: string;
  visitingFrom?: string;
  adults?: number;
  children?: number;
  expectedArrivalTime?: string;
  expectedDepartureTime?: string;
  notes?: string;
}
