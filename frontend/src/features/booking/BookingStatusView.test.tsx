import { act, render, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { Provider } from 'react-redux';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { server } from '../../mocks/server';
import { store } from '../../store';
import { bookingApi } from './bookingApi';
import { BookingStatusView } from './BookingStatusView';
import type { Booking } from '../../types/staff';

function booking(status: Booking['status']): Booking {
  return {
    bookingId: 'b1',
    roomId: 'r1',
    guestName: 'Jane Doe',
    checkIn: '2026-12-01',
    checkOut: '2026-12-03',
    status,
    cancellationReason: null,
    transactionId: null,
  };
}

function renderView() {
  return render(
    <Provider store={store}>
      <BookingStatusView bookingId="b1" />
    </Provider>,
  );
}

beforeEach(() => {
  store.dispatch(bookingApi.util.resetApiState());
  // shouldAdvanceTime: real wall-clock time still passes for testing-library's
  // own internal findBy*/waitFor polling (which uses timers too); only the
  // explicit vi.advanceTimersByTimeAsync calls below simulate the 10s jumps.
  vi.useFakeTimers({ shouldAdvanceTime: true });
});

afterEach(() => {
  vi.useRealTimers();
});

test('renders the HELD message while the booking is held', async () => {
  server.use(http.get('*/api/v1/bookings/b1', () => HttpResponse.json(booking('HELD'))));
  renderView();

  expect(await screen.findByText(/holding the room/i)).toBeInTheDocument();
});

test('polls every 10s while HELD and stops once CONFIRMED', async () => {
  let callCount = 0;
  server.use(
    http.get('*/api/v1/bookings/b1', () => {
      callCount += 1;
      // Settles on the second fetch.
      return HttpResponse.json(booking(callCount >= 2 ? 'CONFIRMED' : 'HELD'));
    }),
  );
  renderView();

  expect(await screen.findByText(/holding the room/i)).toBeInTheDocument();
  expect(callCount).toBe(1);

  await act(() => vi.advanceTimersByTimeAsync(10_000));
  expect(await screen.findByText('Confirmed')).toBeInTheDocument();
  expect(callCount).toBe(2);

  // Polling has stopped — advancing another full interval must not fetch again.
  await act(() => vi.advanceTimersByTimeAsync(10_000));
  expect(callCount).toBe(2);
});

test('polls until CANCELLED, then stops, same as CONFIRMED', async () => {
  let callCount = 0;
  server.use(
    http.get('*/api/v1/bookings/b1', () => {
      callCount += 1;
      return HttpResponse.json(booking(callCount >= 2 ? 'CANCELLED' : 'HELD'));
    }),
  );
  renderView();

  await screen.findByText(/holding the room/i);
  await act(() => vi.advanceTimersByTimeAsync(10_000));
  expect(await screen.findByText('Cancelled')).toBeInTheDocument();

  await act(() => vi.advanceTimersByTimeAsync(10_000));
  expect(callCount).toBe(2);
});

test('a 503/unreachable backend shows RetryBanner', async () => {
  server.use(http.get('*/api/v1/bookings/b1', () => HttpResponse.json({ error: 'unavailable' }, { status: 503 })));
  renderView();

  expect(await screen.findByRole('alert')).toBeInTheDocument();
});
