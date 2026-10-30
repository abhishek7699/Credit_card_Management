import { http, HttpResponse } from 'msw';
import { expect, test } from 'vitest';
import { server } from '../../mocks/server';
import { store } from '../../store';
import { staffAuthApi } from './staffAuthApi';

test('staffLogin posts the role and returns the token', async () => {
  let receivedBody: unknown;
  server.use(
    http.post('*/dev/login', async ({ request }) => {
      receivedBody = await request.json();
      return HttpResponse.json({ token: 'tok-real-query' });
    }),
  );

  const result = await store
    .dispatch(staffAuthApi.endpoints.staffLogin.initiate('FRONT_DESK'))
    .unwrap();

  expect(receivedBody).toEqual({ role: 'FRONT_DESK' });
  expect(result).toEqual({ token: 'tok-real-query' });
});
