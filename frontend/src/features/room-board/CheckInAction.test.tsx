import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { beforeEach, expect, test, vi } from 'vitest';
import { store } from '../../store';
import { CheckInAction } from './CheckInAction';

const mockTrigger = vi.fn();
const mockMutationState: { isLoading: boolean; isError: boolean } = {
  isLoading: false,
  isError: false,
};

vi.mock('../booking/bookingApi', async () => {
  const actual = await vi.importActual<typeof import('../booking/bookingApi')>('../booking/bookingApi');
  return {
    ...actual,
    useCheckInMutation: () => [mockTrigger, mockMutationState],
  };
});

function renderAction(guestName: string | null = 'Jane Doe') {
  return render(
    <Provider store={store}>
      <CheckInAction bookingId="b1" guestName={guestName} />
    </Provider>,
  );
}

beforeEach(() => {
  mockMutationState.isLoading = false;
  mockMutationState.isError = false;
  mockTrigger.mockReset();
  mockTrigger.mockReturnValue({ unwrap: () => Promise.resolve({}) });
});

test('shows the arriving guest\'s name', () => {
  renderAction('Jane Doe');
  expect(screen.getByText(/jane doe is arriving today/i)).toBeInTheDocument();
});

test('falls back to a generic message when no guest name is given', () => {
  renderAction(null);
  expect(screen.getByText(/a guest is arriving today/i)).toBeInTheDocument();
});

test('clicking Check In calls the mutation with the booking id, then shows confirmation', async () => {
  renderAction();
  await userEvent.click(screen.getByRole('button', { name: /check in/i }));

  expect(mockTrigger).toHaveBeenCalledWith('b1');
  expect(await screen.findByText(/checked in\./i)).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /check in/i })).not.toBeInTheDocument();
});

test('a failed check-in shows an inline error and stays on the action', async () => {
  mockTrigger.mockReturnValue({ unwrap: () => Promise.reject(new Error('conflict')) });
  mockMutationState.isError = true;
  renderAction();
  await userEvent.click(screen.getByRole('button', { name: /check in/i }));

  expect(screen.getByRole('alert')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: /check in/i })).toBeInTheDocument();
});

test('the button is disabled and shows a loading label while in flight', () => {
  mockMutationState.isLoading = true;
  renderAction();
  expect(screen.getByRole('button', { name: /checking in/i })).toBeDisabled();
});
