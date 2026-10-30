import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { beforeEach, expect, test, vi } from 'vitest';
import { store } from '../../store';
import { logout } from './staffSessionSlice';
import { LoginScreen } from './LoginScreen';

const mockTrigger = vi.fn();
const mockMutationState: { isLoading: boolean; isError: boolean } = {
  isLoading: false,
  isError: false,
};

vi.mock('./staffAuthApi', () => ({
  useStaffLoginMutation: () => [mockTrigger, mockMutationState],
}));

function renderScreen() {
  return render(
    <Provider store={store}>
      <LoginScreen />
    </Provider>,
  );
}

beforeEach(() => {
  store.dispatch(logout());
  mockMutationState.isLoading = false;
  mockMutationState.isError = false;
  mockTrigger.mockReset();
  mockTrigger.mockReturnValue({ unwrap: () => Promise.resolve({ token: 'tok-login' }) });
});

test('renders both roles, defaulting to Front Desk', () => {
  renderScreen();
  expect(screen.getByLabelText(/front desk/i)).toBeChecked();
  expect(screen.getByLabelText(/housekeeping/i)).not.toBeChecked();
});

test('selecting Housekeeping and submitting calls login with that role', async () => {
  renderScreen();
  await userEvent.click(screen.getByLabelText(/housekeeping/i));
  await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
  expect(mockTrigger).toHaveBeenCalledWith('HOUSEKEEPING');
});

test('submitting with the default role calls login with FRONT_DESK', async () => {
  renderScreen();
  await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
  expect(mockTrigger).toHaveBeenCalledWith('FRONT_DESK');
});

test('a successful login dispatches staffSession/login with the returned token', async () => {
  renderScreen();
  await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
  expect(store.getState().staffSession).toEqual({
    role: 'FRONT_DESK',
    token: 'tok-login',
    status: 'authenticated',
  });
});

test('a failed login shows an inline error and does not change session state', async () => {
  mockMutationState.isError = true;
  mockTrigger.mockReturnValue({ unwrap: () => Promise.reject(new Error('rejected')) });
  renderScreen();
  await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
  expect(screen.getByRole('alert')).toBeInTheDocument();
  expect(store.getState().staffSession.status).toBe('anonymous');
});

test('submit button is disabled and shows a loading label while signing in', () => {
  mockMutationState.isLoading = true;
  renderScreen();
  const btn = screen.getByRole('button', { name: /signing in/i });
  expect(btn).toBeDisabled();
});
