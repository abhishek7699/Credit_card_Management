import { useEffect, useState } from 'react';
import { api } from '../../store/api';
import type { Booking, BookingFormValues, CardDetails, Charge, ChargeType } from '../../types/staff';

const POLLING_INTERVAL_MS = 10_000;

interface CreateBookingResponse {
  bookingId: string;
  status: Booking['status'];
}

interface AddChargeArgs {
  bookingId: string;
  type: ChargeType;
  description: string;
  amount: number;
}

interface CheckOutArgs {
  bookingId: string;
  card?: CardDetails;
}

export const bookingApi = api.injectEndpoints({
  endpoints: (build) => ({
    createBooking: build.mutation<CreateBookingResponse, BookingFormValues>({
      query: (values) => ({
        url: '/api/v1/bookings',
        method: 'POST',
        body: {
          roomId: values.roomId,
          checkIn: values.checkIn,
          checkOut: values.checkOut,
          guestName: values.guestName,
          card: values.card,
        },
      }),
    }),
    getBooking: build.query<Booking, string>({
      query: (bookingId) => `/api/v1/bookings/${bookingId}`,
      providesTags: (_result, _error, bookingId) => [{ type: 'Booking', id: bookingId }],
    }),
    checkIn: build.mutation<Booking, string>({
      query: (bookingId) => ({ url: `/api/v1/bookings/${bookingId}/check-in`, method: 'POST' }),
      invalidatesTags: (_result, _error, bookingId) => [{ type: 'Booking', id: bookingId }],
    }),
    checkOut: build.mutation<Booking, CheckOutArgs>({
      query: ({ bookingId, card }) => ({
        url: `/api/v1/bookings/${bookingId}/check-out`,
        method: 'POST',
        body: card ? { card } : undefined,
      }),
      invalidatesTags: (_result, _error, { bookingId }) => [{ type: 'Booking', id: bookingId }],
    }),
    addCharge: build.mutation<Charge, AddChargeArgs>({
      query: ({ bookingId, type, description, amount }) => ({
        url: `/api/v1/bookings/${bookingId}/charges`,
        method: 'POST',
        body: { type, description, amount },
      }),
      invalidatesTags: (_result, _error, { bookingId }) => [{ type: 'Booking', id: bookingId }],
    }),
  }),
});

export const {
  useCreateBookingMutation,
  useCheckInMutation,
  useCheckOutMutation,
  useAddChargeMutation,
} = bookingApi;

const { useGetBookingQuery: useGetBookingQueryBase } = bookingApi;

/** Polls every 10s while HELD or CHECKOUT_PENDING (research.md §5, extended for
 *  layer 2c's own checkout saga); stops once the booking reaches a final state
 *  (FR-012) — pollingInterval itself is a plain number in this RTK Query version,
 *  so the stop condition lives in component state, not the option. */
export function useGetBookingQuery(bookingId: string) {
  const [pollingInterval, setPollingInterval] = useState(POLLING_INTERVAL_MS);
  const result = useGetBookingQueryBase(bookingId, { pollingInterval });

  useEffect(() => {
    if (result.data && result.data.status !== 'HELD' && result.data.status !== 'CHECKOUT_PENDING') {
      setPollingInterval(0);
    }
  }, [result.data]);

  return result;
}
