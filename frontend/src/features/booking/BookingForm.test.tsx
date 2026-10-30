import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { Provider } from 'react-redux';
import { beforeEach, expect, test, vi } from 'vitest';
import { server } from '../../mocks/server';
import { store } from '../../store';
import { bookingApi } from './bookingApi';
import { BookingForm } from './BookingForm';

const mockTrigger = vi.fn();
const mockMutationState: { isLoading: boolean; isError: boolean } = {
  isLoading: false,
  isError: false,
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
  await userEvent.type(screen.getByLabelText(/check-in/i), '2026-12-01');
  await userEvent.type(screen.getByLabelText(/check-out/i), '2026-12-03');
  await userEvent.type(screen.getByLabelText(/card number/i), '4111111111111111');
  await userEvent.type(screen.getByLabelText(/expiry/i), '12/30');
  await userEvent.type(screen.getByLabelText(/cvv/i), '123');
  await userEvent.type(screen.getByLabelText(/cardholder name/i), 'Jane Doe');
}

beforeEach(() => {
  store.dispatch(bookingApi.util.resetApiState());
  mockMutationState.isLoading = false;
  mockMutationState.isError = false;
  mockTrigger.mockReset();
  mockTrigger.mockReturnValue({ unwrap: () => Promise.resolve({ bookingId: 'b1', status: 'HELD' }) });
  // BookingStatusView's own useGetBookingQuery is NOT mocked above — give it
  // something real to fetch for the tests that reach that screen.
  server.use(
    http.get('*/api/v1/bookings/b1', () =>
      HttpResponse.json({
        bookingId: 'b1',
        roomId: 'room-1',
        guestName: 'Jane Doe',
        checkIn: '2026-12-01',
        checkOut: '2026-12-03',
        status: 'HELD',
        cancellationReason: null,
        transactionId: null,
      }),
    ),
  );
});

test('shows required-field errors on empty submit', async () => {
  renderForm();
  await userEvent.click(screen.getByRole('button', { name: /submit booking/i }));
  expect(screen.getByText(/guest name is required/i)).toBeInTheDocument();
  expect(screen.getByText(/check-in date is required/i)).toBeInTheDocument();
  expect(screen.getByText(/card number is required/i)).toBeInTheDocument();
});

test('check-out not after check-in shows an error', async () => {
  renderForm();
  await userEvent.type(screen.getByLabelText(/check-in/i), '2026-12-05');
  await userEvent.type(screen.getByLabelText(/check-out/i), '2026-12-01');
  await userEvent.click(screen.getByRole('button', { name: /submit booking/i }));
  expect(screen.getByText(/check-out must be after check-in/i)).toBeInTheDocument();
});

test('submits the exact payload shape the API expects, scoped to the given room', async () => {
  renderForm();
  await fillValidForm();
  await userEvent.click(screen.getByRole('button', { name: /submit booking/i }));

  expect(mockTrigger).toHaveBeenCalledWith({
    roomId: 'room-1',
    guestName: 'Jane Doe',
    checkIn: '2026-12-01',
    checkOut: '2026-12-03',
    card: {
      cardNumber: '4111111111111111',
      expiry: '12/30',
      cvv: '123',
      cardholderName: 'Jane Doe',
    },
  });
});

test('after a successful submit, shows the booking status view instead of the form', async () => {
  renderForm();
  await fillValidForm();
  await userEvent.click(screen.getByRole('button', { name: /submit booking/i }));

  expect(await screen.findByText(/booking status/i)).toBeInTheDocument();
  expect(screen.queryByLabelText(/card number/i)).not.toBeInTheDocument();
});

test('card details never appear in sessionStorage, localStorage, or the Redux store after a successful submit', async () => {
  renderForm();
  await fillValidForm();
  await userEvent.click(screen.getByRole('button', { name: /submit booking/i }));
  await screen.findByText(/booking status/i);

  const haystacks = [
    JSON.stringify(store.getState()),
    JSON.stringify({ ...localStorage }),
    JSON.stringify({ ...sessionStorage }),
    Object.keys(localStorage).map((k) => localStorage.getItem(k)).join(''),
    Object.keys(sessionStorage).map((k) => sessionStorage.getItem(k)).join(''),
  ].join('\n');

  expect(haystacks).not.toContain('4111111111111111');
  expect(haystacks).not.toContain('123'); // the CVV entered above
});

test('a failed submit shows an inline error and clears the card fields (no stored-value retry)', async () => {
  mockTrigger.mockReturnValue({ unwrap: () => Promise.reject(new Error('declined')) });
  renderForm();
  await fillValidForm();
  await userEvent.click(screen.getByRole('button', { name: /submit booking/i }));

  expect(await screen.findByLabelText(/card number/i)).toHaveValue('');
  expect(screen.getByLabelText(/guest name/i)).toHaveValue('');
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
