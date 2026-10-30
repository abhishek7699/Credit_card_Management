import { http, HttpResponse } from 'msw';
import { beforeEach, expect, test } from 'vitest';
import { server } from '../../mocks/server';
import { store } from '../../store';
import { bookingApi } from './bookingApi';

beforeEach(() => {
  store.dispatch(bookingApi.util.resetApiState());
});

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

test('checkOut with no card posts an empty body to /api/v1/bookings/{id}/check-out', async () => {
  let receivedMethod = '';
  let receivedBody: unknown;
  server.use(
    http.post('*/api/v1/bookings/b1/check-out', async ({ request }) => {
      receivedMethod = request.method;
      receivedBody = await request.text();
      return HttpResponse.json({
        bookingId: 'b1',
        roomId: 'room-1',
        guestName: 'Jane Doe',
        checkIn: '2026-12-01',
        checkOut: '2026-12-03',
        status: 'CHECKED_OUT',
        cancellationReason: null,
        transactionId: 'txn-1',
        charges: [],
        balanceDue: 0,
      });
    }),
  );

  const result = await store.dispatch(bookingApi.endpoints.checkOut.initiate({ bookingId: 'b1' })).unwrap();

  expect(receivedMethod).toBe('POST');
  expect(receivedBody).toBe('');
  expect(result.status).toBe('CHECKED_OUT');
});

test('checkOut with a card posts the card payload to /api/v1/bookings/{id}/check-out', async () => {
  let receivedBody: unknown;
  const card = { cardNumber: '4111111111111111', expiry: '12/30', cvv: '123', cardholderName: 'Jane Doe' };
  server.use(
    http.post('*/api/v1/bookings/b1/check-out', async ({ request }) => {
      receivedBody = await request.json();
      return HttpResponse.json({
        bookingId: 'b1',
        roomId: 'room-1',
        guestName: 'Jane Doe',
        checkIn: '2026-12-01',
        checkOut: '2026-12-03',
        status: 'CHECKOUT_PENDING',
        cancellationReason: null,
        transactionId: 'txn-1',
        charges: [],
        balanceDue: 24.5,
      });
    }),
  );

  const result = await store.dispatch(bookingApi.endpoints.checkOut.initiate({ bookingId: 'b1', card })).unwrap();

  expect(receivedBody).toEqual({ card });
  expect(result.status).toBe('CHECKOUT_PENDING');
});

test('addCharge posts to /api/v1/bookings/{id}/charges', async () => {
  let receivedBody: unknown;
  server.use(
    http.post('*/api/v1/bookings/b1/charges', async ({ request }) => {
      receivedBody = await request.json();
      return HttpResponse.json(
        { chargeId: 'c1', type: 'ROOM_SERVICE', description: 'Club sandwich', amount: 18.5, createdAt: '2026-10-30T18:04:11Z' },
        { status: 201 },
      );
    }),
  );

  const result = await store
    .dispatch(
      bookingApi.endpoints.addCharge.initiate({
        bookingId: 'b1',
        type: 'ROOM_SERVICE',
        description: 'Club sandwich',
        amount: 18.5,
      }),
    )
    .unwrap();

  expect(receivedBody).toEqual({ type: 'ROOM_SERVICE', description: 'Club sandwich', amount: 18.5 });
  expect(result.chargeId).toBe('c1');
});
