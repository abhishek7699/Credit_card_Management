import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { Provider } from 'react-redux';
import { store } from '../../store';
import { MOCK_ROOMS } from '../../mocks/mockHotelData';
import type { RoomInfo } from '../../types/hotel';
import FloorPlan from './FloorPlan';

const mockState: { data: RoomInfo[] | undefined; isLoading: boolean; isError: boolean } = {
  data: undefined,
  isLoading: false,
  isError: false,
};

vi.mock('./floorPlanApi', () => ({
  useGetRoomsQuery: () => mockState,
}));

function renderFloorPlan() {
  return render(
    <Provider store={store}>
      <MemoryRouter>
        <FloorPlan />
      </MemoryRouter>
    </Provider>,
  );
}

beforeEach(() => {
  mockState.data = MOCK_ROOMS;
  mockState.isLoading = false;
  mockState.isError = false;
});

test('renders 10 room elements', () => {
  renderFloorPlan();
  // Each room has a data-testid="room-NNN"
  const rooms = MOCK_ROOMS.map((r) => screen.getByTestId(`room-${r.number}`));
  expect(rooms).toHaveLength(10);
});

test('OCCUPIED room has red fill', () => {
  renderFloorPlan();
  const occupiedRoom = MOCK_ROOMS.find((r) => r.status === 'OCCUPIED');
  expect(occupiedRoom).toBeDefined();
  const el = screen.getByTestId(`room-${occupiedRoom!.number}`);
  const rect = el.querySelector('rect');
  expect(rect).toHaveAttribute('fill', '#fca5a5');
});

test('READY room has green fill', () => {
  renderFloorPlan();
  const readyRoom = MOCK_ROOMS.find((r) => r.status === 'READY');
  expect(readyRoom).toBeDefined();
  const el = screen.getByTestId(`room-${readyRoom!.number}`);
  const rect = el.querySelector('rect');
  expect(rect).toHaveAttribute('fill', '#86efac');
});

test('NEEDS_CLEANING room has yellow fill', () => {
  renderFloorPlan();
  const cleaningRoom = MOCK_ROOMS.find((r) => r.status === 'NEEDS_CLEANING');
  expect(cleaningRoom).toBeDefined();
  const el = screen.getByTestId(`room-${cleaningRoom!.number}`);
  const rect = el.querySelector('rect');
  expect(rect).toHaveAttribute('fill', '#fde68a');
});

test('clicking an occupied room opens side panel with guest name', async () => {
  renderFloorPlan();
  const occupiedRoom = MOCK_ROOMS.find((r) => r.status === 'OCCUPIED' && r.guestName);
  expect(occupiedRoom).toBeDefined();
  await userEvent.click(screen.getByTestId(`room-${occupiedRoom!.number}`));
  expect(screen.getByRole('dialog')).toBeInTheDocument();
  expect(screen.getByText(occupiedRoom!.guestName!)).toBeInTheDocument();
});

test('clicking a vacant room shows "Vacant" in side panel', async () => {
  renderFloorPlan();
  const vacantRoom = MOCK_ROOMS.find((r) => r.guestName === null);
  expect(vacantRoom).toBeDefined();
  await userEvent.click(screen.getByTestId(`room-${vacantRoom!.number}`));
  expect(screen.getByText('Vacant')).toBeInTheDocument();
});

test('clicking "Mark Cleaned" closes panel and shows toast', async () => {
  renderFloorPlan();
  await userEvent.click(screen.getByTestId('room-101'));
  await userEvent.click(screen.getByRole('button', { name: /mark cleaned/i }));
  expect(screen.queryByRole('dialog')).toBeNull();
  expect(screen.getByText(/marked as cleaned/i)).toBeInTheDocument();
});
