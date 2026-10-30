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

export interface CardDetails {
  cardNumber: string;
  expiry: string;
  cvv: string;
  cardholderName: string;
}

/** Submitted straight to the API — never constructed into, or read back from, any
 *  persisted Redux state or storage (FR-013). */
export interface BookingFormValues {
  roomId: string;
  guestName: string;
  checkIn: string;
  checkOut: string;
  card: CardDetails;
}
