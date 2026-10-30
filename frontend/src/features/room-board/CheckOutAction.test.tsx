import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { beforeEach, expect, test, vi } from 'vitest';
import { store } from '../../store';
import { CheckOutAction } from './CheckOutAction';

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

function renderAction(guestName: string | null = 'Jane Doe') {
  return render(
    <Provider store={store}>
      <CheckOutAction bookingId="b1" guestName={guestName} />
    </Provider>,
  );
}

beforeEach(() => {
  mockMutationState.isLoading = false;
  mockMutationState.isError = false;
  mockTrigger.mockReset();
  mockTrigger.mockReturnValue({ unwrap: () => Promise.resolve({}) });
});

test("shows the occupying guest's name", () => {
  renderAction('Jane Doe');
  expect(screen.getByText(/jane doe is currently occupying/i)).toBeInTheDocument();
});

test('falls back to a generic message when no guest name is given', () => {
  renderAction(null);
  expect(screen.getByText(/this guest is currently occupying/i)).toBeInTheDocument();
});

test('never renders any payment, charge, or balance element', () => {
  renderAction();
  expect(screen.queryByLabelText(/card number/i)).not.toBeInTheDocument();
  expect(screen.queryByText(/balance/i)).not.toBeInTheDocument();
  expect(screen.queryByText(/charge/i)).not.toBeInTheDocument();
});

test('clicking Check Out calls the mutation with the booking id, then shows confirmation', async () => {
  renderAction();
  await userEvent.click(screen.getByRole('button', { name: /check out/i }));

  expect(mockTrigger).toHaveBeenCalledWith('b1');
  expect(await screen.findByText(/checked out\./i)).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /check out/i })).not.toBeInTheDocument();
});

test('a failed check-out shows an inline error and stays on the action', async () => {
  mockTrigger.mockReturnValue({ unwrap: () => Promise.reject(new Error('conflict')) });
  mockMutationState.isError = true;
  renderAction();
  await userEvent.click(screen.getByRole('button', { name: /check out/i }));

  expect(screen.getByRole('alert')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: /check out/i })).toBeInTheDocument();
});

test('the button is disabled and shows a loading label while in flight', () => {
  mockMutationState.isLoading = true;
  renderAction();
  expect(screen.getByRole('button', { name: /checking out/i })).toBeDisabled();
});
