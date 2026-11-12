import { render, screen } from '@testing-library/react';
import { expect, test, vi } from 'vitest';
import { RoomTile } from './RoomTile';
import type { RoomBoardEntry } from '../../types/staff';

function entry(overrides: Partial<RoomBoardEntry>): RoomBoardEntry {
  return {
    roomId: 'r1',
    roomNumber: '101',
    position: 1,
    status: 'OCCUPIED',
    guestName: 'Jane Doe',
    checkIn: '2026-12-01',
    checkOut: '2026-12-03',
    currentBookingId: 'b1',
    arrivingBookingId: null,
    arrivingGuestName: null,
    hasBalanceDue: null,
    ...overrides,
  };
}

test('renders a balance-due badge when hasBalanceDue is true', () => {
  render(<RoomTile room={entry({ hasBalanceDue: true })} onClick={vi.fn()} />);
  expect(screen.getByTestId('balance-due-badge')).toBeInTheDocument();
});

test('does not render a balance-due badge when hasBalanceDue is false', () => {
  render(<RoomTile room={entry({ hasBalanceDue: false })} onClick={vi.fn()} />);
  expect(screen.queryByTestId('balance-due-badge')).not.toBeInTheDocument();
});

test('does not render a balance-due badge when hasBalanceDue is null (not occupied)', () => {
  render(<RoomTile room={entry({ status: 'VACANT_CLEAN', guestName: null, hasBalanceDue: null })} onClick={vi.fn()} />);
  expect(screen.queryByTestId('balance-due-badge')).not.toBeInTheDocument();
});
