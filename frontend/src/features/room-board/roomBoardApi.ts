import { api } from '../../store/api';
import type { RoomBoardFloor } from '../../types/staff';

const POLLING_INTERVAL_MS = 10_000;

export const roomBoardApi = api.injectEndpoints({
  endpoints: (build) => ({
    getRoomBoard: build.query<RoomBoardFloor[], void>({
      query: () => '/api/v1/rooms/board',
    }),
  }),
});

const { useGetRoomBoardQuery: useGetRoomBoardQueryBase } = roomBoardApi;

/** Always polls every 10s (FR-008) — centralized here so every caller gets the
 *  same cadence without repeating the option at each call site. */
export function useGetRoomBoardQuery() {
  return useGetRoomBoardQueryBase(undefined, { pollingInterval: POLLING_INTERVAL_MS });
}
