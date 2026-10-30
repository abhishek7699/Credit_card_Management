import { api } from '../../store/api';
import type { StaffRole } from '../../types/staff';

interface LoginResponse {
  token: string;
}

export const staffAuthApi = api.injectEndpoints({
  endpoints: (build) => ({
    staffLogin: build.mutation<LoginResponse, StaffRole>({
      query: (role) => ({ url: '/dev/login', method: 'POST', body: { role } }),
    }),
  }),
});

export const { useStaffLoginMutation } = staffAuthApi;
