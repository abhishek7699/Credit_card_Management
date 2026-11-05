import { useEffect, useState } from 'react';
import { api } from '../../store/api';
import type { Booking, BookingFormValues, CardDetails, Charge, ChargeType } from '../../types/staff';

const POLLING_INTERVAL_MS = 10_000;

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
    // FR-020: the booking is confirmed synchronously -- this response already IS
    // the confirmed Booking, the same shape getBooking returns, not a thin
    // "created" response to poll from.
    createBooking: build.mutation<Booking, BookingFormValues>({
      query: (values) => ({
        url: '/api/v1/bookings',
        method: 'POST',
        body: values,
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

/** Polls every 10s only while CHECKOUT_PENDING (FR-007) -- booking creation no
 *  longer has an intermediate state to poll for at all (it's CONFIRMED the
 *  instant it's created, 2026-10-31 rework), so this hook is reached only from
 *  the checkout-with-balance flow now. Stops once the attempt resolves one way
 *  or the other (FR-010/FR-011) — pollingInterval itself is a plain number in
 *  this RTK Query version, so the stop condition lives in component state, not
 *  the option. */
export function useGetBookingQuery(bookingId: string) {
  const [pollingInterval, setPollingInterval] = useState(POLLING_INTERVAL_MS);
  const result = useGetBookingQueryBase(bookingId, { pollingInterval });

  useEffect(() => {
    if (result.data && result.data.status !== 'CHECKOUT_PENDING') {
      setPollingInterval(0);
    }
  }, [result.data]);

  return result;
}
