import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { Provider } from 'react-redux';
import { store } from '../../store';
import { MOCK_RATE_CALENDAR, MOCK_ROOM_TYPES } from '../../mocks/mockHotelData';
import type { RateCalendarEntry, RoomType } from '../../types/hotel';
import RoomManagement from './RoomManagement';

const mockRoomTypes: { data: RoomType[] | undefined; isLoading: boolean; isError: boolean } = {
  data: undefined,
  isLoading: false,
  isError: false,
};

const mockRates: { data: RateCalendarEntry[] | undefined; isLoading: boolean; isError: boolean } = {
  data: undefined,
  isLoading: false,
  isError: false,
};

vi.mock('./roomManagementApi', () => ({
  useGetRoomTypesQuery: () => mockRoomTypes,
  useGetRatesQuery: () => mockRates,
}));

function renderRoomManagement() {
  return render(
    <Provider store={store}>
      <MemoryRouter>
        <RoomManagement />
      </MemoryRouter>
    </Provider>,
  );
}

beforeEach(() => {
  mockRoomTypes.data = MOCK_ROOM_TYPES;
  mockRoomTypes.isLoading = false;
  mockRoomTypes.isError = false;
  mockRates.data = MOCK_RATE_CALENDAR;
  mockRates.isLoading = false;
  mockRates.isError = false;
});

test('renders "Room Types" heading', () => {
  renderRoomManagement();
  expect(screen.getByRole('heading', { name: /room types/i })).toBeInTheDocument();
});

test('renders all 3 room type cards', () => {
  renderRoomManagement();
  expect(screen.getByTestId('room-type-card-rt1')).toBeInTheDocument();
  expect(screen.getByTestId('room-type-card-rt2')).toBeInTheDocument();
  expect(screen.getByTestId('room-type-card-rt3')).toBeInTheDocument();
});

test('each card shows base price', () => {
  renderRoomManagement();
  // Multiple $149 elements exist (cards + rate calendar), so check count ≥ 1
  expect(screen.getAllByText('$149').length).toBeGreaterThan(0);
  expect(screen.getAllByText('$299').length).toBeGreaterThan(0);
  expect(screen.getAllByText('$449').length).toBeGreaterThan(0);
});

test('amenities badges render for first room type', () => {
  renderRoomManagement();
  expect(screen.getAllByText('WiFi').length).toBeGreaterThan(0);
  expect(screen.getAllByText('TV').length).toBeGreaterThan(0);
});

test('"Add Room Type" button opens the modal', async () => {
  renderRoomManagement();
  await userEvent.click(screen.getByRole('button', { name: /add room type/i }));
  expect(screen.getByRole('dialog', { name: /add room type/i })).toBeInTheDocument();
});

test('modal "Cancel" button closes the modal', async () => {
  renderRoomManagement();
  await userEvent.click(screen.getByRole('button', { name: /add room type/i }));
  await userEvent.click(screen.getByRole('button', { name: /cancel/i }));
  expect(screen.queryByRole('dialog')).toBeNull();
});

test('modal "Save" button shows toast and closes the modal', async () => {
  renderRoomManagement();
  await userEvent.click(screen.getByRole('button', { name: /add room type/i }));
  await userEvent.click(screen.getByRole('button', { name: /^save$/i }));
  expect(screen.queryByRole('dialog')).toBeNull();
  expect(screen.getByText(/room type saved/i)).toBeInTheDocument();
});

test('renders "Rate Calendar" heading with correct column count', () => {
  renderRoomManagement();
  expect(screen.getByRole('heading', { name: /rate calendar/i })).toBeInTheDocument();
  // Date column + 3 room type columns = 4 column headers
  const headers = screen.getAllByRole('columnheader');
  expect(headers.length).toBe(4);
});
