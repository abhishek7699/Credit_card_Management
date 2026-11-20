import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { expect, test } from 'vitest';
import BookingConfirmation from './BookingConfirmation';
import type { OnlineBookingConfirmation } from './types';

const confirmation: OnlineBookingConfirmation = {
  bookingId: 'b-777',
  status: 'CONFIRMED',
  roomNumber: '102',
  roomType: 'STANDARD',
  checkIn: '2030-01-10',
  checkOut: '2030-01-12',
  nights: 2,
  total: 258,
  payAtCheckout: true,
};

function renderAt(entry: { pathname: string; state?: unknown }) {
  return render(
    <MemoryRouter initialEntries={[entry]}>
      <Routes>
        <Route path="/booked/:bookingId" element={<BookingConfirmation />} />
      </Routes>
    </MemoryRouter>,
  );
}

test('shows the booking reference and the room details right after booking', () => {
  renderAt({ pathname: '/booked/b-777', state: confirmation });

  expect(screen.getByTestId('booking-reference')).toHaveTextContent('b-777');
  expect(screen.getByText('102 (STANDARD)')).toBeInTheDocument();
  expect(screen.getByText('2 nights')).toBeInTheDocument();
  expect(screen.getByText('$258.00')).toBeInTheDocument();
  expect(screen.getByText(/nothing is charged now/i)).toBeInTheDocument();
});

test('after a reload there are no details in the state, so the reference alone is shown', () => {
  renderAt({ pathname: '/booked/b-777' });

  expect(screen.getByTestId('booking-reference')).toHaveTextContent('b-777');
  expect(screen.getByText(/room details are not shown after a reload/i)).toBeInTheDocument();
  expect(screen.queryByText('$258.00')).not.toBeInTheDocument();
});

test('ignores navigation state that is not a confirmation', () => {
  renderAt({ pathname: '/booked/b-777', state: { unrelated: true } });
  expect(screen.getByText(/room details are not shown after a reload/i)).toBeInTheDocument();
});

test('ignores a non-object navigation state', () => {
  renderAt({ pathname: '/booked/b-777', state: 'text' });
  expect(screen.getByText(/room details are not shown after a reload/i)).toBeInTheDocument();
});

test('ignores a null navigation state', () => {
  renderAt({ pathname: '/booked/b-777', state: null });
  expect(screen.getByText(/room details are not shown after a reload/i)).toBeInTheDocument();
});
