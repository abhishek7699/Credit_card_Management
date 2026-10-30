import { http, HttpResponse } from 'msw';
import { expect, test } from 'vitest';
import { server } from '../../mocks/server';
import { store } from '../../store';
import { roomStatusApi } from './roomStatusApi';

test('updateRoomStatus PATCHes the room with the requested status', async () => {
  let receivedMethod = '';
  let receivedBody: unknown;
  server.use(
    http.patch('*/api/v1/rooms/r1/status', async ({ request }) => {
      receivedMethod = request.method;
      receivedBody = await request.json();
      return HttpResponse.json({ roomId: 'r1', status: 'VACANT_CLEAN' });
    }),
  );

  const result = await store
    .dispatch(roomStatusApi.endpoints.updateRoomStatus.initiate({ roomId: 'r1', status: 'VACANT_CLEAN' }))
    .unwrap();

  expect(receivedMethod).toBe('PATCH');
  expect(receivedBody).toEqual({ status: 'VACANT_CLEAN' });
  expect(result).toEqual({ roomId: 'r1', status: 'VACANT_CLEAN' });
});
