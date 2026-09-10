import { api } from '../../store/api';
import type { Transaction } from '../../types/transaction';

const transactionApi = api.injectEndpoints({
  endpoints: (build) => ({
    getTransaction: build.query<Transaction, string>({
      query: (id) => `/api/v1/transactions/${id}`,
    }),
  }),
});

export const { useGetTransactionQuery } = transactionApi;
