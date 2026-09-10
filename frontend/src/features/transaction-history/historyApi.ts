import { api } from '../../store/api';
import type { TransactionListItem } from '../../types/transaction';

const historyApi = api.injectEndpoints({
  endpoints: (build) => ({
    listTransactions: build.query<TransactionListItem[], void>({
      query: () => '/api/v1/reports/transactions',
    }),
  }),
});

export const { useListTransactionsQuery } = historyApi;
