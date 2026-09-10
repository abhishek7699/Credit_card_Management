import { render, screen } from '@testing-library/react';
import { Provider } from 'react-redux';
import { MemoryRouter } from 'react-router-dom';
import { store } from '../../store';
import { MOCK_TRANSACTIONS } from '../../mocks/mockData';
import type { TransactionListItem } from '../../types/transaction';
import TransactionHistory from './TransactionHistory';

const mockQueryState: {
  data: TransactionListItem[] | undefined;
  isLoading: boolean;
  isError: boolean;
} = {
  data: undefined,
  isLoading: false,
  isError: false,
};

vi.mock('./historyApi', () => ({
  useListTransactionsQuery: () => mockQueryState,
}));

function renderHistory() {
  return render(
    <Provider store={store}>
      <MemoryRouter>
        <TransactionHistory />
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
  renderHistory();
  expect(screen.getByRole('status', { name: /loading/i })).toBeInTheDocument();
});

test('renders table rows from mock data', () => {
  mockQueryState.data = MOCK_TRANSACTIONS;
  renderHistory();
  const rows = screen.getAllByRole('row');
  // header row + 5 data rows
  expect(rows).toHaveLength(MOCK_TRANSACTIONS.length + 1);
});

test('renders card numbers in the table', () => {
  mockQueryState.data = MOCK_TRANSACTIONS;
  renderHistory();
  expect(screen.getAllByText(/\*{4}/)).toHaveLength(MOCK_TRANSACTIONS.length);
});

test('shows No transactions found for empty array', () => {
  mockQueryState.data = [];
  renderHistory();
  expect(screen.getByText(/no transactions found/i)).toBeInTheDocument();
});

test('shows error alert when isError is true', () => {
  mockQueryState.isError = true;
  renderHistory();
  expect(screen.getByRole('alert')).toBeInTheDocument();
});

test('AUTHORIZED status badge has green class', () => {
  mockQueryState.data = MOCK_TRANSACTIONS;
  renderHistory();
  const badge = screen.getAllByText(/authorized/i)[0];
  expect(badge).toBeDefined();
  expect(badge!.className).toContain('green');
});

test('PENDING status badge has yellow class', () => {
  mockQueryState.data = MOCK_TRANSACTIONS;
  renderHistory();
  const badge = screen.getByText(/pending/i);
  expect(badge.className).toContain('yellow');
});
