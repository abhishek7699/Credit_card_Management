import type { BaseQueryFn } from '@reduxjs/toolkit/query';
import { MOCK_TRANSACTION, MOCK_TRANSACTIONS } from './mockData';
import {
  MOCK_DASHBOARD_DATA,
  MOCK_PAYMENTS_DATA,
  MOCK_RATE_CALENDAR,
  MOCK_RESERVATIONS,
  MOCK_ROOMS,
  MOCK_ROOM_TYPES,
} from './mockHotelData';

interface QueryArgs {
  url: string;
  method?: string;
  body?: unknown;
}

export const mockBaseQuery: BaseQueryFn<string | QueryArgs> = async (args, _api, _extra) => {
  await new Promise<void>((r) => setTimeout(r, 300));

  const url = typeof args === 'string' ? args : args.url;
  const method = typeof args === 'string' ? 'GET' : (args.method ?? 'GET');

  if (method === 'POST' && url === '/api/v1/transactions') {
    return { data: { ...MOCK_TRANSACTION, id: `txn-mock-${Date.now()}` } };
  }
  if (method === 'GET' && url.startsWith('/api/v1/transactions/')) {
    return { data: MOCK_TRANSACTION };
  }
  if (method === 'GET' && url === '/api/v1/reports/transactions') {
    return { data: MOCK_TRANSACTIONS };
  }
  if (method === 'GET' && url === '/api/v1/pms/dashboard') {
    return { data: MOCK_DASHBOARD_DATA };
  }
  if (method === 'GET' && url === '/api/v1/pms/rooms') {
    return { data: MOCK_ROOMS };
  }
  if (method === 'GET' && url === '/api/v1/pms/payments') {
    return { data: MOCK_PAYMENTS_DATA };
  }
  if (method === 'GET' && url === '/api/v1/pms/reservations') {
    return { data: MOCK_RESERVATIONS };
  }
  if (method === 'GET' && url === '/api/v1/pms/room-types') {
    return { data: MOCK_ROOM_TYPES };
  }
  if (method === 'GET' && url === '/api/v1/pms/rates') {
    return { data: MOCK_RATE_CALENDAR };
  }

  return { error: { status: 404, data: 'Not found' } };
};
