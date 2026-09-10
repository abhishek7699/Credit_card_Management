import { render, screen } from '@testing-library/react';
import { Provider } from 'react-redux';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { store } from '../../store';
import { MOCK_TRANSACTION } from '../../mocks/mockData';
import type { Transaction } from '../../types/transaction';
import TransactionStatus from './TransactionStatus';

const mockQueryState: {
  data: Transaction | undefined;
  isLoading: boolean;
  isError: boolean;
} = {
  data: undefined,
  isLoading: false,
  isError: false,
};

vi.mock('./transactionApi', () => ({
  useGetTransactionQuery: () => mockQueryState,
}));

function renderStatus(id = 'txn-001') {
  return render(
    <Provider store={store}>
      <MemoryRouter initialEntries={[`/transactions/${id}`]}>
        <Routes>
          <Route path="/transactions/:id" element={<TransactionStatus />} />
        </Routes>
      </MemoryRouter>
    </Provider>,
  );
}

beforeEach(() => {
  mockQueryState.data = undefined;
  mockQueryState.isLoading = false;
  mockQueryState.isError = false;
});

test('shows loading spinner when isLoading is true', () => {
  mockQueryState.isLoading = true;
  renderStatus();
  expect(screen.getByRole('status', { name: /loading/i })).toBeInTheDocument();
});

test('shows PENDING badge with yellow class', () => {
  mockQueryState.data = { ...MOCK_TRANSACTION, status: 'PENDING' };
  renderStatus();
  const badge = screen.getByText(/pending/i);
  expect(badge).toBeInTheDocument();
  expect(badge.className).toContain('yellow');
});

test('shows AUTHORIZED badge with green class', () => {
  mockQueryState.data = { ...MOCK_TRANSACTION, status: 'AUTHORIZED' };
  renderStatus();
  const badge = screen.getByText(/authorized/i);
  expect(badge.className).toContain('green');
});

test('shows DECLINED badge with red class', () => {
  mockQueryState.data = { ...MOCK_TRANSACTION, status: 'DECLINED' };
  renderStatus();
  const badge = screen.getByText(/declined/i);
  expect(badge.className).toContain('red');
});

test('shows FAILED badge with red class', () => {
  mockQueryState.data = { ...MOCK_TRANSACTION, status: 'FAILED' };
  renderStatus();
  const badge = screen.getByText(/failed/i);
  expect(badge.className).toContain('red');
});

test('renders masked card number and amount', () => {
  mockQueryState.data = MOCK_TRANSACTION;
  renderStatus();
  expect(screen.getByText(MOCK_TRANSACTION.cardNumber)).toBeInTheDocument();
  expect(screen.getByText(/99\.99/)).toBeInTheDocument();
});

test('shows error message when isError is true', () => {
  mockQueryState.isError = true;
  renderStatus();
  expect(screen.getByRole('alert')).toBeInTheDocument();
});
