import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { GuestBookingForm } from './GuestBookingForm';
import type { OfferRoom } from './types';

const room: OfferRoom = {
  roomId: 'r1',
  roomNumber: '201',
  roomType: 'DELUXE',
  nightlyRate: 179,
  total: 358,
  included: ['Wi-Fi', 'Breakfast'],
};

test('blocks submit and shows field errors when required details are missing', async () => {
  const onSubmit = vi.fn();
  render(<GuestBookingForm room={room} onSubmit={onSubmit} submitting={false} serverError={null} />);

  await userEvent.click(screen.getByRole('button', { name: 'Confirm booking' }));

  expect(screen.getByText('Enter your name')).toBeInTheDocument();
  expect(screen.getByText('Enter a phone number')).toBeInTheDocument();
  expect(onSubmit).not.toHaveBeenCalled();
});

test('rejects a malformed email and a zero adult count', async () => {
  const onSubmit = vi.fn();
  render(<GuestBookingForm room={room} onSubmit={onSubmit} submitting={false} serverError={null} />);

  await userEvent.type(screen.getByLabelText('Full name'), 'Jane');
  await userEvent.type(screen.getByLabelText('Phone'), '555');
  await userEvent.type(screen.getByLabelText('Email (optional)'), 'not-an-email');
  await userEvent.clear(screen.getByLabelText('Adults'));
  await userEvent.type(screen.getByLabelText('Adults'), '0');
  await userEvent.click(screen.getByRole('button', { name: 'Confirm booking' }));

  expect(screen.getByText('Enter a valid email address')).toBeInTheDocument();
  expect(screen.getByText('At least one adult')).toBeInTheDocument();
  expect(onSubmit).not.toHaveBeenCalled();
});

test('submits trimmed details, with a blank email sent as null', async () => {
  const onSubmit = vi.fn();
  render(<GuestBookingForm room={room} onSubmit={onSubmit} submitting={false} serverError={null} />);

  await userEvent.type(screen.getByLabelText('Full name'), '  Jane Doe  ');
  await userEvent.type(screen.getByLabelText('Phone'), ' +1 555 0100 ');
  await userEvent.clear(screen.getByLabelText('Children'));
  await userEvent.type(screen.getByLabelText('Children'), '2');
  await userEvent.click(screen.getByRole('button', { name: 'Confirm booking' }));

  expect(onSubmit).toHaveBeenCalledWith({
    guestName: 'Jane Doe',
    guestPhone: '+1 555 0100',
    guestEmail: null,
    adults: 1,
    children: 2,
  });
});

test('shows the server message and disables submit while submitting', () => {
  render(<GuestBookingForm room={room} onSubmit={vi.fn()} submitting serverError="Room is taken" />);

  expect(screen.getByRole('alert')).toHaveTextContent('Room is taken');
  expect(screen.getByRole('button', { name: 'Confirming…' })).toBeDisabled();
});
