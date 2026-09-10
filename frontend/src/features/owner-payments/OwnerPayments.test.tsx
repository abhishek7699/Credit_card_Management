import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { Provider } from 'react-redux';
import { store } from '../../store';
import { MOCK_PAYMENTS_DATA } from '../../mocks/mockHotelData';
import type { PaymentsData } from '../../types/hotel';
import OwnerPayments from './OwnerPayments';

const mockState: { data: PaymentsData | undefined; isLoading: boolean; isError: boolean } = {
  data: undefined,
  isLoading: false,
  isError: false,
};

vi.mock('./paymentsApi', () => ({
  useGetPaymentsQuery: () => mockState,
}));

function renderPayments() {
  return render(
    <Provider store={store}>
      <MemoryRouter>
        <OwnerPayments />
      </MemoryRouter>
    </Provider>,
  );
}

beforeEach(() => {
  mockState.data = MOCK_PAYMENTS_DATA;
  mockState.isLoading = false;
  mockState.isError = false;
});

test('renders "Next Payout" heading text', () => {
  renderPayments();
  expect(screen.getByText(/next payout/i)).toBeInTheDocument();
});

test('renders next payout amount from mock data', () => {
  renderPayments();
  expect(screen.getByText('$3,120.00')).toBeInTheDocument();
});

test('renders processed this month amount', () => {
  renderPayments();
  expect(screen.getByText('$12,850.00')).toBeInTheDocument();
});

test('renders correct number of table rows', () => {
  renderPayments();
  const rows = screen.getAllByRole('row');
  // 1 header row + 8 data rows
  expect(rows).toHaveLength(9);
});

test('SETTLED status badge has green class', () => {
  renderPayments();
  const settledBadges = screen.getAllByText('Settled');
  expect(settledBadges[0]).toHaveAttribute('data-status', 'SETTLED');
  expect(settledBadges[0]?.className).toContain('bg-green-100');
});

test('FAILED status badge has red class', () => {
  renderPayments();
  const failedBadge = screen.getByText('Failed');
  expect(failedBadge).toHaveAttribute('data-status', 'FAILED');
  expect(failedBadge.className).toContain('bg-red-100');
});

test('Fee column header is present', () => {
  renderPayments();
  expect(screen.getByRole('columnheader', { name: /fee/i })).toBeInTheDocument();
});

test('Export CSV button shows toast', async () => {
  renderPayments();
  await userEvent.click(screen.getByRole('button', { name: /export csv/i }));
  expect(screen.getByText(/csv export coming soon/i)).toBeInTheDocument();
});
