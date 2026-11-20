import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { AvailableRooms } from './AvailableRooms';
import type { OfferRoom } from './types';

const rooms: OfferRoom[] = [
  { roomId: 'r1', roomNumber: '101', roomType: 'STANDARD', nightlyRate: 129, total: 387, included: ['Wi-Fi'] },
  { roomId: 'r2', roomNumber: '301', roomType: 'SUITE', nightlyRate: 259, total: 777, included: [] },
];

test('shows the nightly rate, the stay total and what is included for each room', () => {
  render(<AvailableRooms rooms={rooms} nights={3} onSelect={vi.fn()} />);

  const first = screen.getByTestId('offer-101');
  expect(first).toHaveTextContent('$129.00 per night');
  expect(first).toHaveTextContent('Total for 3 nights: $387.00');
  expect(screen.getByRole('list', { name: 'Included in room 101' })).toHaveTextContent('Wi-Fi');
});

test('omits the included list when a room includes nothing', () => {
  render(<AvailableRooms rooms={rooms} nights={3} onSelect={vi.fn()} />);
  expect(screen.queryByRole('list', { name: 'Included in room 301' })).not.toBeInTheDocument();
});

test('uses the singular for a one-night stay', () => {
  render(<AvailableRooms rooms={rooms.slice(0, 1)} nights={1} onSelect={vi.fn()} />);
  expect(screen.getByTestId('offer-101')).toHaveTextContent('Total for 1 night:');
});

test('selecting a room hands that room to the caller', async () => {
  const onSelect = vi.fn();
  render(<AvailableRooms rooms={rooms} nights={3} onSelect={onSelect} />);

  await userEvent.click(screen.getByRole('button', { name: 'Book room 301' }));

  expect(onSelect).toHaveBeenCalledWith(rooms[1]);
});

test('says so when the list is empty', () => {
  render(<AvailableRooms rooms={[]} nights={2} onSelect={vi.fn()} />);
  expect(screen.getByText(/no rooms are free/i)).toBeInTheDocument();
});
