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

async function fillCard(overrides: Partial<Record<'number' | 'expiry' | 'cvv' | 'name', string>> = {}) {
  await userEvent.type(screen.getByLabelText('Card number'), overrides.number ?? '5102 5899 9999 9913');
  await userEvent.type(screen.getByLabelText('Expiry (MM/YY)'), overrides.expiry ?? '10/50');
  await userEvent.type(screen.getByLabelText('CVV'), overrides.cvv ?? '123');
  await userEvent.type(screen.getByLabelText('Name on card'), overrides.name ?? 'Jane Candy-Smith');
}

test('blocks submit and shows field errors when required details are missing', async () => {
  const onSubmit = vi.fn();
  render(<GuestBookingForm room={room} onSubmit={onSubmit} submitting={false} serverError={null} />);

  await userEvent.click(screen.getByRole('button', { name: 'Confirm booking' }));

  expect(screen.getByText('Enter your name')).toBeInTheDocument();
  expect(screen.getByText('Enter a phone number')).toBeInTheDocument();
  expect(screen.getByText('Card number is required')).toBeInTheDocument();
  expect(screen.getByText('Expiry date is required')).toBeInTheDocument();
  expect(screen.getByText('CVV is required')).toBeInTheDocument();
  expect(screen.getByText('Cardholder name is required')).toBeInTheDocument();
  expect(onSubmit).not.toHaveBeenCalled();
});

test('blocks submit on a malformed card, without sending anything', async () => {
  const onSubmit = vi.fn();
  render(<GuestBookingForm room={room} onSubmit={onSubmit} submitting={false} serverError={null} />);

  await userEvent.type(screen.getByLabelText('Full name'), 'Jane');
  await userEvent.type(screen.getByLabelText('Phone'), '555');
  await fillCard({ number: '1234', expiry: '1050', cvv: '12' });
  await userEvent.click(screen.getByRole('button', { name: 'Confirm booking' }));

  expect(screen.getByText('Enter a valid card number (13–19 digits)')).toBeInTheDocument();
  expect(screen.getByText('Use MM/YY format')).toBeInTheDocument();
  expect(screen.getByText('CVV must be 3 or 4 digits')).toBeInTheDocument();
  expect(onSubmit).not.toHaveBeenCalled();
});

test('tells the guest nothing is charged and the card is never stored', () => {
  render(<GuestBookingForm room={room} onSubmit={vi.fn()} submitting={false} serverError={null} />);

  expect(screen.getByText(/nothing is charged, and we never store your card number or security code/i))
    .toBeInTheDocument();
});

test('rejects a malformed email and a zero adult count', async () => {
  const onSubmit = vi.fn();
  render(<GuestBookingForm room={room} onSubmit={onSubmit} submitting={false} serverError={null} />);

  await userEvent.type(screen.getByLabelText('Full name'), 'Jane');
  await userEvent.type(screen.getByLabelText('Phone'), '555');
  await fillCard();
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
  await fillCard({ name: '  Jane Candy-Smith  ' });
  await userEvent.click(screen.getByRole('button', { name: 'Confirm booking' }));

  expect(onSubmit).toHaveBeenCalledWith({
    guestName: 'Jane Doe',
    guestPhone: '+1 555 0100',
    guestEmail: null,
    adults: 1,
    children: 2,
    // spaces are stripped from the number; the other card fields are trimmed
    card: { cardNumber: '5102589999999913', expiry: '10/50', cvv: '123', cardholderName: 'Jane Candy-Smith' },
  });
});

test('shows the server message and disables submit while submitting', () => {
  render(<GuestBookingForm room={room} onSubmit={vi.fn()} submitting serverError="Room is taken" />);

  expect(screen.getByRole('alert')).toHaveTextContent('Room is taken');
  expect(screen.getByRole('button', { name: 'Confirming…' })).toBeDisabled();
});
