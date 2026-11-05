import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { beforeEach, expect, test, vi } from 'vitest';
import { store } from '../../store';
import { bookingApi } from './bookingApi';
import { BookingForm } from './BookingForm';

const mockTrigger = vi.fn();
const mockMutationState: { isLoading: boolean; isError: boolean } = {
  isLoading: false,
  isError: false,
};

const CONFIRMED_BOOKING = {
  bookingId: 'b1',
  roomId: 'room-1',
  guestName: 'Jane Doe',
  guestPhone: '+1-555-0100',
  guestEmail: null,
  visitingFrom: null,
  adults: 1,
  children: 0,
  checkIn: '2026-12-01',
  checkOut: '2026-12-03',
  expectedArrivalTime: null,
  expectedDepartureTime: null,
  notes: null,
  status: 'CONFIRMED' as const,
  cancellationReason: null,
  charges: [],
  balanceDue: 240,
};

vi.mock('./bookingApi', async () => {
  const actual = await vi.importActual<typeof import('./bookingApi')>('./bookingApi');
  return {
    ...actual,
    useCreateBookingMutation: () => [mockTrigger, mockMutationState],
  };
});

function renderForm() {
  return render(
    <Provider store={store}>
      <BookingForm roomId="room-1" />
    </Provider>,
  );
}

async function fillValidForm() {
  await userEvent.type(screen.getByLabelText(/guest name/i), 'Jane Doe');
  await userEvent.type(screen.getByLabelText(/phone/i), '+1-555-0100');
  await userEvent.type(screen.getByLabelText(/check-in/i), '2026-12-01');
  await userEvent.type(screen.getByLabelText(/check-out/i), '2026-12-03');
}

beforeEach(() => {
  store.dispatch(bookingApi.util.resetApiState());
  mockMutationState.isLoading = false;
  mockMutationState.isError = false;
  mockTrigger.mockReset();
  mockTrigger.mockReturnValue({ unwrap: () => Promise.resolve(CONFIRMED_BOOKING) });
});

test('shows required-field errors on empty submit', async () => {
  renderForm();
  await userEvent.click(screen.getByRole('button', { name: /submit booking/i }));
  expect(screen.getByText(/guest name is required/i)).toBeInTheDocument();
  expect(screen.getByText(/phone number is required/i)).toBeInTheDocument();
  expect(screen.getByText(/check-in date is required/i)).toBeInTheDocument();
});

test('no card field is ever rendered -- booking creation takes no card (FR-017)', () => {
  renderForm();
  expect(screen.queryByLabelText(/card number/i)).not.toBeInTheDocument();
  expect(screen.queryByLabelText(/cvv/i)).not.toBeInTheDocument();
});

test('check-out not after check-in shows an error', async () => {
  renderForm();
  await userEvent.type(screen.getByLabelText(/check-in/i), '2026-12-05');
  await userEvent.type(screen.getByLabelText(/check-out/i), '2026-12-01');
  await userEvent.click(screen.getByRole('button', { name: /submit booking/i }));
  expect(screen.getByText(/check-out must be after check-in/i)).toBeInTheDocument();
});

test('submits the exact payload shape the API expects, scoped to the given room, with no card', async () => {
  renderForm();
  await fillValidForm();
  await userEvent.click(screen.getByRole('button', { name: /submit booking/i }));

  expect(mockTrigger).toHaveBeenCalledWith({
    roomId: 'room-1',
    guestName: 'Jane Doe',
    guestPhone: '+1-555-0100',
    checkIn: '2026-12-01',
    checkOut: '2026-12-03',
    guestEmail: undefined,
    visitingFrom: undefined,
    adults: undefined,
    children: undefined,
    expectedArrivalTime: undefined,
    expectedDepartureTime: undefined,
    notes: undefined,
  });
});

test('submits optional fields when filled in', async () => {
  renderForm();
  await fillValidForm();
  await userEvent.type(screen.getByLabelText(/email/i), 'jane@example.com');
  await userEvent.type(screen.getByLabelText(/visiting from/i), 'Chicago, IL');
  await userEvent.type(screen.getByLabelText(/adults/i), '2');
  await userEvent.type(screen.getByLabelText(/children/i), '1');
  await userEvent.type(screen.getByLabelText(/expected arrival/i), '15:00');
  await userEvent.type(screen.getByLabelText(/expected departure/i), '11:00');
  await userEvent.type(screen.getByLabelText(/notes/i), 'Requested a crib');
  await userEvent.click(screen.getByRole('button', { name: /submit booking/i }));

  expect(mockTrigger).toHaveBeenCalledWith(
    expect.objectContaining({
      guestEmail: 'jane@example.com',
      visitingFrom: 'Chicago, IL',
      adults: 2,
      children: 1,
      expectedArrivalTime: '15:00',
      expectedDepartureTime: '11:00',
      notes: 'Requested a crib',
    }),
  );
});

test('after a successful submit, shows the confirmation directly from the response (no poll, no card screen)', async () => {
  renderForm();
  await fillValidForm();
  await userEvent.click(screen.getByRole('button', { name: /submit booking/i }));

  expect(await screen.findByText(/booking confirmed for jane doe/i)).toBeInTheDocument();
  expect(screen.getByText(/balance due at checkout: \$240\.00/i)).toBeInTheDocument();
  expect(screen.queryByLabelText(/guest name/i)).not.toBeInTheDocument();
});

test('a failed submit shows an inline error and clears the fields', async () => {
  mockTrigger.mockReturnValue({ unwrap: () => Promise.reject(new Error('rejected')) });
  renderForm();
  await fillValidForm();
  await userEvent.click(screen.getByRole('button', { name: /submit booking/i }));

  expect(await screen.findByLabelText(/guest name/i)).toHaveValue('');
});

test('submit button is disabled and shows a loading label while submitting', () => {
  mockMutationState.isLoading = true;
  renderForm();
  expect(screen.getByRole('button', { name: /submitting/i })).toBeDisabled();
});

test('shows an inline error banner when the mutation reports an error', () => {
  mockMutationState.isError = true;
  renderForm();
  expect(screen.getByRole('alert')).toBeInTheDocument();
});
