import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { beforeEach, expect, test, vi } from 'vitest';
import { store } from '../../store';
import { CheckOutAction } from './CheckOutAction';
import type { BookingStatus } from '../../types/staff';

const mockTrigger = vi.fn();
const mockMutationState: { isLoading: boolean; isError: boolean } = {
  isLoading: false,
  isError: false,
};

vi.mock('../booking/bookingApi', async () => {
  const actual = await vi.importActual<typeof import('../booking/bookingApi')>('../booking/bookingApi');
  return {
    ...actual,
    useCheckOutMutation: () => [mockTrigger, mockMutationState],
  };
});

function renderAction(
  guestName: string | null = 'Jane Doe',
  status: BookingStatus = 'CHECKED_IN',
  balanceDue = 0,
) {
  return render(
    <Provider store={store}>
      <CheckOutAction bookingId="b1" guestName={guestName} status={status} balanceDue={balanceDue} />
    </Provider>,
  );
}

beforeEach(() => {
  mockMutationState.isLoading = false;
  mockMutationState.isError = false;
  mockTrigger.mockReset();
  mockTrigger.mockReturnValue({ unwrap: () => Promise.resolve({}) });
});

// ── zero balance (US4) ───────────────────────────────────────────────────────

test("zero balance: shows the occupying guest's name", () => {
  renderAction('Jane Doe');
  expect(screen.getByText(/jane doe is currently occupying/i)).toBeInTheDocument();
});

test('zero balance: falls back to a generic message when no guest name is given', () => {
  renderAction(null);
  expect(screen.getByText(/this guest is currently occupying/i)).toBeInTheDocument();
});

test('zero balance: never renders any payment, charge, or balance element', () => {
  renderAction();
  expect(screen.queryByLabelText(/card number/i)).not.toBeInTheDocument();
  expect(screen.queryByText(/balance/i)).not.toBeInTheDocument();
  expect(screen.queryByText(/charge/i)).not.toBeInTheDocument();
});

test('zero balance: clicking Check Out calls the mutation with just the booking id', async () => {
  renderAction();
  await userEvent.click(screen.getByRole('button', { name: /check out/i }));

  expect(mockTrigger).toHaveBeenCalledWith({ bookingId: 'b1' });
});

test('zero balance: a failed check-out shows an inline error and stays on the action', async () => {
  mockTrigger.mockReturnValue({ unwrap: () => Promise.reject(new Error('conflict')) });
  mockMutationState.isError = true;
  renderAction();
  await userEvent.click(screen.getByRole('button', { name: /check out/i }));

  expect(screen.getByRole('alert')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: /check out/i })).toBeInTheDocument();
});

test('zero balance: the button is disabled and shows a loading label while in flight', () => {
  mockMutationState.isLoading = true;
  renderAction();
  expect(screen.getByRole('button', { name: /checking out/i })).toBeDisabled();
});

// ── status-driven states (US3) ───────────────────────────────────────────────

test('CHECKED_OUT: shows the confirmation, no button', () => {
  renderAction('Jane Doe', 'CHECKED_OUT', 0);
  expect(screen.getByText(/checked out\./i)).toBeInTheDocument();
  expect(screen.queryByRole('button')).not.toBeInTheDocument();
});

test('CHECKOUT_PENDING: shows a processing message, no button or card form', () => {
  renderAction('Jane Doe', 'CHECKOUT_PENDING', 24.5);
  expect(screen.getByText(/processing checkout/i)).toBeInTheDocument();
  expect(screen.queryByRole('button')).not.toBeInTheDocument();
  expect(screen.queryByLabelText(/card number/i)).not.toBeInTheDocument();
});

// ── balance due (US3) ────────────────────────────────────────────────────────

test('balance due: shows the amount and a card form instead of a bare button', () => {
  renderAction('Jane Doe', 'CHECKED_IN', 24.5);
  expect(screen.getByText(/balance due of \$24\.50/i)).toBeInTheDocument();
  expect(screen.getByLabelText(/card number/i)).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /^check out$/i })).not.toBeInTheDocument();
});

test('balance due: falls back to a generic message when no guest name is given', () => {
  renderAction(null, 'CHECKED_IN', 24.5);
  expect(screen.getByText(/this guest has a balance due of \$24\.50/i)).toBeInTheDocument();
});

test('balance due: submitting the card form calls the mutation with the card payload', async () => {
  renderAction('Jane Doe', 'CHECKED_IN', 24.5);

  await userEvent.type(screen.getByLabelText(/card number/i), '4111111111111111');
  await userEvent.type(screen.getByLabelText(/expiry/i), '12/30');
  await userEvent.type(screen.getByLabelText(/cvv/i), '123');
  await userEvent.type(screen.getByLabelText(/cardholder name/i), 'Jane Doe');
  await userEvent.click(screen.getByRole('button', { name: /charge and check out/i }));

  expect(mockTrigger).toHaveBeenCalledWith({
    bookingId: 'b1',
    card: { cardNumber: '4111111111111111', expiry: '12/30', cvv: '123', cardholderName: 'Jane Doe' },
  });
});

test('balance due: a declined/failed outcome shows an inline error, card form stays', async () => {
  mockTrigger.mockReturnValue({ unwrap: () => Promise.reject(new Error('declined')) });
  mockMutationState.isError = true;
  renderAction('Jane Doe', 'CHECKED_IN', 24.5);

  await userEvent.type(screen.getByLabelText(/card number/i), '4111111111111111');
  await userEvent.type(screen.getByLabelText(/expiry/i), '12/30');
  await userEvent.type(screen.getByLabelText(/cvv/i), '123');
  await userEvent.type(screen.getByLabelText(/cardholder name/i), 'Jane Doe');
  await userEvent.click(screen.getByRole('button', { name: /charge and check out/i }));

  expect(screen.getByRole('alert')).toBeInTheDocument();
  expect(screen.getByLabelText(/card number/i)).toBeInTheDocument();
});
