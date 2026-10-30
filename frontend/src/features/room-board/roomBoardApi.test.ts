import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { server } from '../../mocks/server';
import { store } from '../../store';
import { roomBoardApi } from './roomBoardApi';
import type { RoomBoardFloor } from '../../types/staff';

const BOARD: RoomBoardFloor[] = [
  {
    floor: 1,
    rooms: [
      {
        roomId: 'r1',
        roomNumber: '101',
        position: 1,
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
];

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
  store.dispatch(roomBoardApi.util.resetApiState());
});

test('getRoomBoard fetches and returns the board shape', async () => {
  server.use(http.get('*/api/v1/rooms/board', () => HttpResponse.json(BOARD)));

  const result = await store.dispatch(roomBoardApi.endpoints.getRoomBoard.initiate()).unwrap();

  expect(result).toEqual(BOARD);
});

test('polls again after the 10s interval elapses', async () => {
  let callCount = 0;
  server.use(
    http.get('*/api/v1/rooms/board', () => {
      callCount += 1;
      return HttpResponse.json(BOARD);
    }),
  );

  const subscription = store.dispatch(
    roomBoardApi.endpoints.getRoomBoard.initiate(undefined, { subscriptionOptions: { pollingInterval: 10_000 } }),
  );
  await subscription;
  expect(callCount).toBe(1);

  await vi.advanceTimersByTimeAsync(10_000);
  expect(callCount).toBe(2);

  subscription.unsubscribe();
});
