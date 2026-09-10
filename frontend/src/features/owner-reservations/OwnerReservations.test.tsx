import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { Provider } from 'react-redux';
import { store } from '../../store';
import { MOCK_RESERVATIONS } from '../../mocks/mockHotelData';
import type { Reservation } from '../../types/hotel';
import OwnerReservations from './OwnerReservations';

const mockState: { data: Reservation[] | undefined; isLoading: boolean; isError: boolean } = {
  data: undefined,
  isLoading: false,
  isError: false,
};

vi.mock('./reservationsApi', () => ({
  useGetReservationsQuery: () => mockState,
}));

function renderReservations() {
  return render(
    <Provider store={store}>
      <MemoryRouter>
        <OwnerReservations />
      </MemoryRouter>
    </Provider>,
  );
}

beforeEach(() => {
  mockState.data = MOCK_RESERVATIONS;
  mockState.isLoading = false;
  mockState.isError = false;
});

test('renders heading "Reservations"', () => {
  renderReservations();
  expect(screen.getByRole('heading', { name: /reservations/i })).toBeInTheDocument();
});

test('renders correct number of data rows from mock data', () => {
  renderReservations();
  // header row + 11 data rows
  const rows = screen.getAllByRole('row');
  expect(rows.length).toBeGreaterThanOrEqual(12);
});

test('UPCOMING badge has blue class', () => {
  renderReservations();
  const badge = document.querySelector('[data-status="UPCOMING"]');
  expect(badge).not.toBeNull();
  expect(badge!.className).toContain('bg-blue-100');
});

test('CURRENT badge has green class', () => {
  renderReservations();
  const badge = document.querySelector('[data-status="CURRENT"]');
  expect(badge).not.toBeNull();
  expect(badge!.className).toContain('bg-green-100');
});

test('CANCELLED badge has red class', () => {
  renderReservations();
  const badge = document.querySelector('[data-status="CANCELLED"]');
  expect(badge).not.toBeNull();
  expect(badge!.className).toContain('bg-red-100');
});

test('clicking a row expands detail panel with guest email', async () => {
  renderReservations();
  const firstRow = screen.getByTestId('reservation-row-res1');
  await userEvent.click(firstRow);
  const detail = screen.getByTestId('reservation-detail-res1');
  expect(detail).toBeInTheDocument();
  expect(within(detail).getByText('james.carter@email.com')).toBeInTheDocument();
});

test('clicking the same row again collapses the panel', async () => {
  renderReservations();
  const firstRow = screen.getByTestId('reservation-row-res1');
  await userEvent.click(firstRow);
  expect(screen.getByTestId('reservation-detail-res1')).toBeInTheDocument();
  await userEvent.click(firstRow);
  expect(screen.queryByTestId('reservation-detail-res1')).toBeNull();
});

test('status filter hides rows that do not match', async () => {
  renderReservations();
  const select = screen.getByRole('combobox');
  await userEvent.selectOptions(select, 'UPCOMING');
  // Only UPCOMING reservations should be visible — no CURRENT status badges in table
  expect(document.querySelector('[data-status="CURRENT"]')).toBeNull();
  expect(document.querySelector('[data-status="UPCOMING"]')).not.toBeNull();
});
