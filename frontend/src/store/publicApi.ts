import { createApi, fetchBaseQuery } from '@reduxjs/toolkit/query/react';
import type {
  AvailabilityResponse,
  OnlineBookingConfirmation,
  OnlineBookingRequest,
} from '../features/front-office/types';

/**
 * The guest-facing API (front office, layer 2d). Deliberately sends NO staff token.
 * The gateway checks any Bearer header even on a public route, so a stale staff token
 * left in this browser would make a guest's request fail with 401.
 */
export const publicApi = createApi({
  reducerPath: 'publicApi',
  baseQuery: fetchBaseQuery({
    baseUrl: import.meta.env['VITE_API_BASE_URL'] as string,
  }),
  endpoints: (build) => ({
    getAvailability: build.query<AvailabilityResponse, { checkIn: string; checkOut: string }>({
      query: ({ checkIn, checkOut }) => ({
        url: '/api/v1/public/availability',
        params: { checkIn, checkOut },
      }),
    }),
    createOnlineBooking: build.mutation<OnlineBookingConfirmation, OnlineBookingRequest>({
      query: (body) => ({
        url: '/api/v1/public/bookings',
        method: 'POST',
        body,
      }),
    }),
  }),
});

export const { useGetAvailabilityQuery, useCreateOnlineBookingMutation } = publicApi;
