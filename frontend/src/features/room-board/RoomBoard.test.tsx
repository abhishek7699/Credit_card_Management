import { render, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { Provider } from 'react-redux';
import { beforeEach, expect, test } from 'vitest';
import { server } from '../../mocks/server';
import { store } from '../../store';
import { login, logout } from '../staff-session/staffSessionSlice';
import { StaffAuthGate } from '../staff-session/StaffAuthGate';
import { roomBoardApi } from './roomBoardApi';
import RoomBoard from './RoomBoard';
import type { RoomBoardFloor } from '../../types/staff';

const BOARD: RoomBoardFloor[] = [
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
      },
      {
        roomId: 'r2',
        roomNumber: '102',
        position: 2,
        status: 'VACANT_CLEAN',
        guestName: null,
        checkIn: null,
        checkOut: null,
        currentBookingId: null,
        arrivingBookingId: null,
        arrivingGuestName: null,
      },
    ],
  },
  {
    floor: 2,
    rooms: [
      {
        roomId: 'r3',
        roomNumber: '201',
        position: 1,
        status: 'DIRTY',
        guestName: null,
        checkIn: null,
        checkOut: null,
        currentBookingId: null,
        arrivingBookingId: null,
        arrivingGuestName: null,
      },
    ],
  },
];

// Importing RoomBoard directly (not through App's lazy()) keeps this test
// synchronous and focused — the lazy-loading wiring itself is covered by
// App.test.tsx.
function renderBoard() {
  return render(
    <Provider store={store}>
      <RoomBoard />
    </Provider>,
  );
}

beforeEach(() => {
  store.dispatch(logout());
  store.dispatch(login({ role: 'FRONT_DESK', token: 'tok-board-test' }));
  store.dispatch(roomBoardApi.util.resetApiState());
});

test('renders every room grouped by floor, ordered by position, colored/labeled by status', async () => {
  server.use(http.get('*/api/v1/rooms/board', () => HttpResponse.json(BOARD)));
  renderBoard();

  expect(await screen.findByText('Floor 1')).toBeInTheDocument();
  expect(screen.getByText('Floor 2')).toBeInTheDocument();

  const room101 = screen.getByTestId('room-101');
  expect(room101).toHaveAttribute('data-status', 'OCCUPIED');
  expect(room101).toHaveTextContent('Jane Doe');

  const room102 = screen.getByTestId('room-102');
  expect(room102).toHaveAttribute('data-status', 'VACANT_CLEAN');
  expect(room102).not.toHaveTextContent('Jane Doe');

  expect(screen.getByTestId('room-201')).toHaveAttribute('data-status', 'DIRTY');
});

test('a non-OCCUPIED room never shows guest details even if present in the payload', async () => {
  // Defensive against a backend bug, not a case the real API produces — the
  // UI must not blindly trust a guestName field outside its documented scope.
  const malformed: RoomBoardFloor[] = [
    {
      floor: 1,
      rooms: [{ ...BOARD[0]!.rooms[1]!, guestName: 'Should Not Show' }],
    },
  ];
  server.use(http.get('*/api/v1/rooms/board', () => HttpResponse.json(malformed)));
  renderBoard();

  await screen.findByText('Floor 1');
  expect(screen.queryByText('Should Not Show')).not.toBeInTheDocument();
});

test('a 503/unreachable backend shows RetryBanner instead of stale or blank content', async () => {
  server.use(http.get('*/api/v1/rooms/board', () => HttpResponse.json({ error: 'unavailable' }, { status: 503 })));
  renderBoard();

  expect(await screen.findByRole('alert')).toBeInTheDocument();
});

test('a 401 mid-session ends the session and renders the login screen (via the real middleware)', async () => {
  server.use(http.get('*/api/v1/rooms/board', () => HttpResponse.json({ error: 'unauthorized' }, { status: 401 })));
  render(
    <Provider store={store}>
      <StaffAuthGate>
        <RoomBoard />
      </StaffAuthGate>
    </Provider>,
  );

  expect(await screen.findByText(/staff sign in/i)).toBeInTheDocument();
});
