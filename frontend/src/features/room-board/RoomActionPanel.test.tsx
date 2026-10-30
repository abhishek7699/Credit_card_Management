import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { expect, test, vi } from 'vitest';
import { store } from '../../store';
import { RoomActionPanel } from './RoomActionPanel';
import type { RoomBoardEntry } from '../../types/staff';

function entry(overrides: Partial<RoomBoardEntry>): RoomBoardEntry {
  return {
    roomId: 'r1',
    roomNumber: '101',
    position: 1,
    status: 'VACANT_CLEAN',
    guestName: null,
    checkIn: null,
    checkOut: null,
    currentBookingId: null,
    arrivingBookingId: null,
    arrivingGuestName: null,
    ...overrides,
  };
}

function renderPanel(room: RoomBoardEntry, role: 'FRONT_DESK' | 'HOUSEKEEPING', onClose = vi.fn()) {
  return render(
    <Provider store={store}>
      <RoomActionPanel room={room} role={role} onClose={onClose} />
    </Provider>,
  );
}

test('a room with an arriving-today booking offers check-in for Front Desk, even while still VACANT_CLEAN', () => {
  renderPanel(
    entry({ status: 'VACANT_CLEAN', arrivingBookingId: 'b-arriving', arrivingGuestName: 'John Smith' }),
    'FRONT_DESK',
  );
  expect(screen.getByText(/john smith is arriving today/i)).toBeInTheDocument();
  expect(screen.queryByLabelText(/guest name/i)).not.toBeInTheDocument();
});

test('a vacant room with no arriving booking offers the booking form for Front Desk', () => {
  renderPanel(entry({ status: 'VACANT_CLEAN' }), 'FRONT_DESK');
  expect(screen.getByLabelText(/guest name/i)).toBeInTheDocument();
});

test('a room with an arriving-today booking offers nothing for Housekeeping', () => {
  renderPanel(
    entry({ status: 'VACANT_CLEAN', arrivingBookingId: 'b-arriving', arrivingGuestName: 'John Smith' }),
    'HOUSEKEEPING',
  );
  expect(screen.queryByText(/arriving today/i)).not.toBeInTheDocument();
  expect(screen.getByText(/no action available/i)).toBeInTheDocument();
});

test('an OCCUPIED room offers check-out for Front Desk', () => {
  renderPanel(
    entry({ status: 'OCCUPIED', currentBookingId: 'b-current', guestName: 'Jane Doe' }),
    'FRONT_DESK',
  );
  expect(screen.getByText(/jane doe is currently occupying/i)).toBeInTheDocument();
});

test('an OCCUPIED room offers nothing for Housekeeping', () => {
  renderPanel(
    entry({ status: 'OCCUPIED', currentBookingId: 'b-current', guestName: 'Jane Doe' }),
    'HOUSEKEEPING',
  );
  expect(screen.queryByText(/currently occupying/i)).not.toBeInTheDocument();
  expect(screen.getByText(/no action available/i)).toBeInTheDocument();
});

test('an OCCUPIED room with a same-day arrival still offers check-out, not check-in (check-in cannot succeed yet)', () => {
  renderPanel(
    entry({
      status: 'OCCUPIED',
      currentBookingId: 'b-current',
      guestName: 'Jane Doe',
      arrivingBookingId: 'b-arriving',
      arrivingGuestName: 'John Smith',
    }),
    'FRONT_DESK',
  );
  expect(screen.getByText(/jane doe is currently occupying/i)).toBeInTheDocument();
  expect(screen.queryByText(/john smith is arriving today/i)).not.toBeInTheDocument();
});

test('a DIRTY room offers a status-change action for Front Desk', () => {
  renderPanel(entry({ status: 'DIRTY' }), 'FRONT_DESK');
  expect(screen.getByRole('button', { name: /mark clean/i })).toBeInTheDocument();
});

test('a DIRTY room offers a status-change action for Housekeeping too', () => {
  renderPanel(entry({ status: 'DIRTY' }), 'HOUSEKEEPING');
  expect(screen.getByRole('button', { name: /mark clean/i })).toBeInTheDocument();
});

test('a MAINTENANCE room offers a status-change action for either role', () => {
  renderPanel(entry({ status: 'MAINTENANCE' }), 'HOUSEKEEPING');
  expect(screen.getByRole('button', { name: /mark clean/i })).toBeInTheDocument();
});

test('renders the room number and calls onClose from the close button', async () => {
  const onClose = vi.fn();
  renderPanel(entry({ roomNumber: '305' }), 'HOUSEKEEPING', onClose);
  expect(screen.getByText('Room 305')).toBeInTheDocument();

  await userEvent.click(screen.getByRole('button', { name: /close panel/i }));
  expect(onClose).toHaveBeenCalledTimes(1);
});

test('calls onClose when the backdrop is clicked', async () => {
  const onClose = vi.fn();
  const { container } = renderPanel(entry({}), 'HOUSEKEEPING', onClose);
  const backdrop = container.querySelector('[aria-hidden="true"]');
  expect(backdrop).not.toBeNull();

  await userEvent.click(backdrop!);
  expect(onClose).toHaveBeenCalledTimes(1);
});
