import { api } from '../../store/api';
import type { SubmitTransactionRequest, Transaction } from '../../types/transaction';

const paymentApi = api.injectEndpoints({
  endpoints: (build) => ({
    submitTransaction: build.mutation<Transaction, SubmitTransactionRequest>({
      query: (body) => ({ url: '/api/v1/transactions', method: 'POST', body }),
    }),
  }),
});

export const { useSubmitTransactionMutation } = paymentApi;
