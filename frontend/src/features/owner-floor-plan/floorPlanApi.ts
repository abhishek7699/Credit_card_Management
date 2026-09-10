import { api } from '../../store/api';
import type { RoomInfo } from '../../types/hotel';

const floorPlanApi = api.injectEndpoints({
  endpoints: (build) => ({
    getRooms: build.query<RoomInfo[], void>({
      query: () => '/api/v1/pms/rooms',
    }),
  }),
});

export const { useGetRoomsQuery } = floorPlanApi;
