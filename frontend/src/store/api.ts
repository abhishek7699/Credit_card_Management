import { createApi, fetchBaseQuery } from '@reduxjs/toolkit/query/react';
import { mockBaseQuery } from '../mocks/mockBaseQuery';

const useMock = import.meta.env['VITE_USE_MOCK_DATA'] === 'true';

// Not RootState (store/index.ts imports this file — importing RootState back
// here would be circular); every existing, unauthenticated consumer of this
// instance (payment-submission, owner-*) simply has no staffSession slice
// value to find, so the header is never added for them.
interface StateWithStaffSession {
  staffSession?: { token: string | null };
}

export const api = createApi({
  reducerPath: 'api',
  baseQuery: useMock
    ? mockBaseQuery
    : fetchBaseQuery({
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
