import { api } from '../../store/api';
import type { DashboardData } from '../../types/hotel';

const dashboardApi = api.injectEndpoints({
  endpoints: (build) => ({
    getDashboard: build.query<DashboardData, void>({
      query: () => '/api/v1/pms/dashboard',
    }),
  }),
});

export const { useGetDashboardQuery } = dashboardApi;
