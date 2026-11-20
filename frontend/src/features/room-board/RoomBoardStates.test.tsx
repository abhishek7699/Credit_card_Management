import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { Provider } from 'react-redux';
import { beforeEach, expect, test } from 'vitest';
import { server } from '../../mocks/server';
import { store } from '../../store';
import { login, logout } from '../staff-session/staffSessionSlice';
import { bookingApi } from '../booking/bookingApi';
import { roomBoardApi } from './roomBoardApi';
import RoomBoard from './RoomBoard';
import type { RoomBoardFloor } from '../../types/staff';

const OCCUPIED_BOARD: RoomBoardFloor[] = [
  {
    floor: 1,
    rooms: [
      {
        roomId: 'r1',
        roomNumber: '101',
        position: 1,
        status: 'OCCUPIED',
        guestName: 'Jane Doe',
        checkIn: '2026-12-01',
        checkOut: '2026-12-03',
        currentBookingId: 'b1',
        arrivingBookingId: null,
        arrivingGuestName: null,
        hasBalanceDue: false,
      },
    ],
  },
];

function renderBoard() {
  return render(
    <Provider store={store}>
      <RoomBoard />
    </Provider>,
  );
}

beforeEach(() => {
  store.dispatch(logout());
  store.dispatch(login({ role: 'FRONT_DESK', token: 'tok-states-test' }));
  store.dispatch(roomBoardApi.util.resetApiState());
  store.dispatch(bookingApi.util.resetApiState());
});

test('a hotel with no rooms yet says so, in plain words, instead of an empty grid', async () => {
  server.use(http.get('*/api/v1/rooms/board', () => HttpResponse.json([])));
  renderBoard();

  expect(await screen.findByText(/no rooms are set up yet/i)).toBeInTheDocument();
});

test('the colour key lists all four room statuses', async () => {
  server.use(http.get('*/api/v1/rooms/board', () => HttpResponse.json(OCCUPIED_BOARD)));
  renderBoard();

  const key = await screen.findByRole('list', { name: /room colour key/i });
  expect(key).toHaveTextContent('Vacant');
  expect(key).toHaveTextContent('Occupied');
  expect(key).toHaveTextContent('Dirty');
  expect(key).toHaveTextContent('Maintenance');
});

test('the board shows a labelled loading state while the first load is in flight', () => {
  server.use(http.get('*/api/v1/rooms/board', () => new Promise(() => {})));
  renderBoard();

  expect(screen.getByText('Loading the room board…')).toBeInTheDocument();
});

test('when the guest record cannot load, the panel says so and offers a retry, not "Loading…" forever', async () => {
  server.use(
    http.get('*/api/v1/rooms/board', () => HttpResponse.json(OCCUPIED_BOARD)),
    http.get('*/api/v1/bookings/b1', () => HttpResponse.json({ error: 'down' }, { status: 500 })),
  );
  renderBoard();

  await userEvent.click(await screen.findByTestId('room-101'));

  expect(await screen.findByText(/we couldn.t load this booking/i)).toBeInTheDocument();
  expect(screen.queryByText(/loading guest details/i)).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: /try again/i })).toBeInTheDocument();
});
