import { api } from '../../store/api';
import type { Reservation } from '../../types/hotel';

const reservationsApi = api.injectEndpoints({
  endpoints: (build) => ({
    getReservations: build.query<Reservation[], void>({
      query: () => '/api/v1/pms/reservations',
    }),
  }),
});

export const { useGetReservationsQuery } = reservationsApi;
