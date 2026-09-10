export type TransactionStatus = 'PENDING' | 'AUTHORIZED' | 'DECLINED' | 'FAILED';

export interface Transaction {
  id: string;
  cardNumber: string;
  cardholderName: string;
  amount: number;
  currency: string;
  status: TransactionStatus;
  createdAt: string;
}

export type TransactionListItem = Omit<Transaction, 'cardholderName'>;

export interface SubmitTransactionRequest {
  cardNumber: string;
  expiry: string;
  cvv: string;
  cardholderName: string;
  amount: number;
  currency: string;
}
