import { api } from '../../store/api';
import type { RateCalendarEntry, RoomType } from '../../types/hotel';

const roomManagementApi = api.injectEndpoints({
  endpoints: (build) => ({
    getRoomTypes: build.query<RoomType[], void>({
      query: () => '/api/v1/pms/room-types',
    }),
    getRates: build.query<RateCalendarEntry[], void>({
      query: () => '/api/v1/pms/rates',
    }),
  }),
});

export const { useGetRoomTypesQuery, useGetRatesQuery } = roomManagementApi;
