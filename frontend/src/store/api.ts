import { createApi, fetchBaseQuery } from '@reduxjs/toolkit/query/react';

// Not RootState (store/index.ts imports this file — importing RootState back
// here would be circular).
interface StateWithStaffSession {
  staffSession?: { token: string | null };
}

export const api = createApi({
  reducerPath: 'api',
  tagTypes: ['Booking'],
  baseQuery: fetchBaseQuery({
    baseUrl: import.meta.env['VITE_API_BASE_URL'] as string,
    prepareHeaders: (headers, { getState }) => {
      const token = (getState() as StateWithStaffSession).staffSession?.token;
      if (token) {
        headers.set('Authorization', `Bearer ${token}`);
      }
      return headers;
    },
  }),
  endpoints: () => ({}),
});
