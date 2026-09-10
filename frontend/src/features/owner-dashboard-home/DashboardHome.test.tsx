import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { Provider } from 'react-redux';
import { store } from '../../store';
import { MOCK_DASHBOARD_DATA } from '../../mocks/mockHotelData';
import type { DashboardData } from '../../types/hotel';
import DashboardHome from './DashboardHome';

const mockState: { data: DashboardData | undefined; isLoading: boolean; isError: boolean } = {
  data: undefined,
  isLoading: false,
  isError: false,
};

vi.mock('./dashboardApi', () => ({
  useGetDashboardQuery: () => mockState,
}));

function renderDashboard() {
  return render(
    <Provider store={store}>
      <MemoryRouter>
        <DashboardHome />
      </MemoryRouter>
    </Provider>,
  );
}

beforeEach(() => {
  mockState.data = MOCK_DASHBOARD_DATA;
  mockState.isLoading = false;
  mockState.isError = false;
});

test('renders all 4 stat card labels', () => {
  renderDashboard();
  expect(screen.getByText('Occupancy Today')).toBeInTheDocument();
  expect(screen.getByText('Revenue Today')).toBeInTheDocument();
  expect(screen.getByText('Arrivals Today')).toBeInTheDocument();
  expect(screen.getByText('Departures Today')).toBeInTheDocument();
});

test('renders stat values from mock data', () => {
  renderDashboard();
  expect(screen.getByText('18/24 rooms')).toBeInTheDocument();
  expect(screen.getByText('$4,280')).toBeInTheDocument();
});

test('renders Needs Attention heading', () => {
  renderDashboard();
  expect(screen.getByText(/needs attention/i)).toBeInTheDocument();
});

test('renders all attention items', () => {
  renderDashboard();
  expect(screen.getByText('Room 204 needs cleaning')).toBeInTheDocument();
  expect(screen.getByText('1 payment declined – Room 112')).toBeInTheDocument();
  expect(screen.getByText('Maintenance request – Room 308')).toBeInTheDocument();
});

test('renders the revenue chart container', () => {
  renderDashboard();
  expect(screen.getByTestId('revenue-chart')).toBeInTheDocument();
});

test('shows loading spinner when isLoading is true', () => {
  mockState.data = undefined;
  mockState.isLoading = true;
  renderDashboard();
  expect(screen.getByRole('status', { name: /loading/i })).toBeInTheDocument();
});
