import { http, HttpResponse } from 'msw';
import { beforeEach, expect, test } from 'vitest';
import { server } from '../mocks/server';
import { api } from './api';
import { store } from './index';
import { login, logout } from '../features/staff-session/staffSessionSlice';

const testApi = api.injectEndpoints({
  endpoints: (build) => ({
    probe: build.query<{ ok: true }, void>({ query: () => '/api/v1/test-probe' }),
  }),
});

beforeEach(() => {
  store.dispatch(logout());
});

test('a request made with no staff session carries no Authorization header', async () => {
  let receivedAuth: string | null = null;
  server.use(
    http.get('*/api/v1/test-probe', ({ request }) => {
      receivedAuth = request.headers.get('Authorization');
      return HttpResponse.json({ ok: true });
    }),
  );

  await store.dispatch(testApi.endpoints.probe.initiate());

  expect(receivedAuth).toBeNull();
});

test('a request made with a staff session carries the bearer token', async () => {
  store.dispatch(login({ role: 'FRONT_DESK', token: 'tok-store-test' }));
  let receivedAuth: string | null = null;
  server.use(
    http.get('*/api/v1/test-probe', ({ request }) => {
      receivedAuth = request.headers.get('Authorization');
      return HttpResponse.json({ ok: true });
    }),
  );

  await store.dispatch(testApi.endpoints.probe.initiate(undefined, { forceRefetch: true }));

  expect(receivedAuth).toBe('Bearer tok-store-test');
});

test('a 401 response dispatches sessionExpired', async () => {
  store.dispatch(login({ role: 'FRONT_DESK', token: 'tok-401-test' }));
  server.use(
    http.get('*/api/v1/test-probe', () => HttpResponse.json({ error: 'unauthorized' }, { status: 401 })),
  );

  await store.dispatch(testApi.endpoints.probe.initiate(undefined, { forceRefetch: true }));

  expect(store.getState().staffSession.status).toBe('expired');
});
