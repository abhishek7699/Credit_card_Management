import { api } from '../../store/api';
import type { PaymentsData } from '../../types/hotel';

const paymentsApi = api.injectEndpoints({
  endpoints: (build) => ({
    getPayments: build.query<PaymentsData, void>({
      query: () => '/api/v1/pms/payments',
    }),
  }),
});

export const { useGetPaymentsQuery } = paymentsApi;
