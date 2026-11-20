/** Shapes of the guest-facing API (booking-service PublicBookingController). The server
 *  decides every price and rule; these types only describe what is displayed. */

export interface OfferRoom {
  roomId: string;
  roomNumber: string;
  roomType: string;
  nightlyRate: number;
  total: number;
  included: string[];
}

export interface AvailabilityResponse {
  checkIn: string;
  checkOut: string;
  nights: number;
  rooms: OfferRoom[];
}

export interface OnlineBookingRequest {
  roomId: string;
  checkIn: string;
  checkOut: string;
  guestName: string;
  guestPhone: string;
  guestEmail: string | null;
  adults: number;
  children: number;
}

export interface OnlineBookingConfirmation {
  bookingId: string;
  status: string;
  roomNumber: string;
  roomType: string;
  checkIn: string;
  checkOut: string;
  nights: number;
  total: number;
  payAtCheckout: boolean;
}
