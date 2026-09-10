import { createApi, fetchBaseQuery } from '@reduxjs/toolkit/query/react';
import { mockBaseQuery } from '../mocks/mockBaseQuery';

const useMock = import.meta.env['VITE_USE_MOCK_DATA'] === 'true';

export const api = createApi({
  reducerPath: 'api',
  baseQuery: useMock
    ? mockBaseQuery
    : fetchBaseQuery({ baseUrl: import.meta.env['VITE_API_BASE_URL'] as string }),
  endpoints: () => ({}),
});
