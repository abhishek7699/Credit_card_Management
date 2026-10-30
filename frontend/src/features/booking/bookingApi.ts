import { useEffect, useState } from 'react';
import { api } from '../../store/api';
import type { Booking, BookingFormValues } from '../../types/staff';

const POLLING_INTERVAL_MS = 10_000;

interface CreateBookingResponse {
  bookingId: string;
  status: Booking['status'];
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
    }),
    checkIn: build.mutation<Booking, string>({
      query: (bookingId) => ({ url: `/api/v1/bookings/${bookingId}/check-in`, method: 'POST' }),
    }),
  }),
});

export const { useCreateBookingMutation, useCheckInMutation } = bookingApi;

const { useGetBookingQuery: useGetBookingQueryBase } = bookingApi;

/** Polls every 10s while HELD (research.md §5); stops once the booking reaches a
 *  final state (FR-012) — pollingInterval itself is a plain number in this RTK
 *  Query version, so the stop condition lives in component state, not the option. */
export function useGetBookingQuery(bookingId: string) {
  const [pollingInterval, setPollingInterval] = useState(POLLING_INTERVAL_MS);
  const result = useGetBookingQueryBase(bookingId, { pollingInterval });

  useEffect(() => {
    if (result.data && result.data.status !== 'HELD') {
      setPollingInterval(0);
    }
  }, [result.data]);

  return result;
}
