import type { Transaction, TransactionListItem } from '../types/transaction';

export const MOCK_TRANSACTION: Transaction = {
  id: 'txn-mock-001',
  cardNumber: '**** **** **** 4242',
  cardholderName: 'Jane Doe',
  amount: 99.99,
  currency: 'USD',
  status: 'AUTHORIZED',
  createdAt: '2026-08-26T10:00:00Z',
};

export const MOCK_TRANSACTIONS: TransactionListItem[] = [
  { id: 'txn-mock-001', cardNumber: '**** **** **** 4242', amount: 99.99, currency: 'USD', status: 'AUTHORIZED', createdAt: '2026-08-26T10:00:00Z' },
  { id: 'txn-mock-002', cardNumber: '**** **** **** 1234', amount: 250.00, currency: 'USD', status: 'PENDING', createdAt: '2026-08-26T09:30:00Z' },
  { id: 'txn-mock-003', cardNumber: '**** **** **** 5678', amount: 45.50, currency: 'USD', status: 'DECLINED', createdAt: '2026-08-26T09:00:00Z' },
  { id: 'txn-mock-004', cardNumber: '**** **** **** 9012', amount: 1200.00, currency: 'USD', status: 'FAILED', createdAt: '2026-08-25T18:00:00Z' },
  { id: 'txn-mock-005', cardNumber: '**** **** **** 3456', amount: 33.00, currency: 'USD', status: 'AUTHORIZED', createdAt: '2026-08-25T14:00:00Z' },
];
