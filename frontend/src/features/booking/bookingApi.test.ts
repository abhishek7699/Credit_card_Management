import { http, HttpResponse } from 'msw';
import { expect, test } from 'vitest';
import { server } from '../../mocks/server';
import { store } from '../../store';
import { bookingApi } from './bookingApi';

test('createBooking posts the exact payload shape to /api/v1/bookings', async () => {
  let receivedBody: unknown;
  server.use(
    http.post('*/api/v1/bookings', async ({ request }) => {
      receivedBody = await request.json();
      return HttpResponse.json({ bookingId: 'b1', status: 'HELD' }, { status: 201 });
    }),
  );

  const payload = {
    roomId: 'room-1',
    guestName: 'Jane Doe',
    checkIn: '2026-12-01',
    checkOut: '2026-12-03',
    card: { cardNumber: '4111111111111111', expiry: '12/30', cvv: '123', cardholderName: 'Jane Doe' },
  };
  const result = await store.dispatch(bookingApi.endpoints.createBooking.initiate(payload)).unwrap();

  expect(receivedBody).toEqual(payload);
  expect(result).toEqual({ bookingId: 'b1', status: 'HELD' });
});

test('getBooking fetches /api/v1/bookings/{id}', async () => {
  server.use(
    http.get('*/api/v1/bookings/b1', () =>
      HttpResponse.json({
        bookingId: 'b1',
        roomId: 'room-1',
        guestName: 'Jane Doe',
        checkIn: '2026-12-01',
        checkOut: '2026-12-03',
        status: 'CONFIRMED',
        cancellationReason: null,
        transactionId: 'txn-1',
      }),
    ),
  );

  const result = await store.dispatch(bookingApi.endpoints.getBooking.initiate('b1')).unwrap();

  expect(result.status).toBe('CONFIRMED');
});

test('checkIn posts to /api/v1/bookings/{id}/check-in', async () => {
  let receivedMethod = '';
  server.use(
    http.post('*/api/v1/bookings/b1/check-in', ({ request }) => {
      receivedMethod = request.method;
      return HttpResponse.json({
        bookingId: 'b1',
        roomId: 'room-1',
        guestName: 'Jane Doe',
        checkIn: '2026-12-01',
        checkOut: '2026-12-03',
        status: 'CHECKED_IN',
        cancellationReason: null,
        transactionId: 'txn-1',
      });
    }),
  );

  const result = await store.dispatch(bookingApi.endpoints.checkIn.initiate('b1')).unwrap();

  expect(receivedMethod).toBe('POST');
  expect(result.status).toBe('CHECKED_IN');
});
