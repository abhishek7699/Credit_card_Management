import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { Provider } from 'react-redux';
import { configureStore } from '@reduxjs/toolkit';
import { api } from '../../store/api';
import authReducer, { login } from '../../store/authSlice';
import { AuthGate } from './AuthGate';

vi.mock('./hashPin', () => ({
  hashPin: (p: string) => Promise.resolve(`hash:${p}`),
}));

function makeStore() {
  return configureStore({
    reducer: { [api.reducerPath]: api.reducer, auth: authReducer },
    middleware: (gDM) => gDM().concat(api.middleware),
  });
}

function renderGate(store = makeStore()) {
  return {
    store,
    ...render(
      <Provider store={store}>
        <MemoryRouter>
          <AuthGate>
            <div>APP_CONTENT</div>
          </AuthGate>
        </MemoryRouter>
      </Provider>,
    ),
  };
}

beforeEach(() => {
  localStorage.clear();
});

test('shows registration form when no stored device', () => {
  renderGate();
  expect(screen.getByRole('heading', { name: /create your pin/i })).toBeInTheDocument();
});

test('registration: PIN mismatch shows error', async () => {
  renderGate();
  await userEvent.type(screen.getByLabelText(/email/i), 'test@test.com');
  await userEvent.type(screen.getByLabelText(/^new pin$/i), '1234');
  await userEvent.type(screen.getByLabelText(/confirm pin/i), '5678');
  await userEvent.click(screen.getByRole('button', { name: /create pin/i }));
  expect(screen.getByRole('alert')).toHaveTextContent('PINs do not match');
});

test('registration: non-4-digit PIN shows error', async () => {
  renderGate();
  await userEvent.type(screen.getByLabelText(/email/i), 'test@test.com');
  await userEvent.type(screen.getByLabelText(/^new pin$/i), '12');
  await userEvent.type(screen.getByLabelText(/confirm pin/i), '12');
  await userEvent.click(screen.getByRole('button', { name: /create pin/i }));
  expect(screen.getByRole('alert')).toHaveTextContent('PIN must be 4 digits');
});

test('successful registration shows app content', async () => {
  renderGate();
  await userEvent.type(screen.getByLabelText(/email/i), 'test@test.com');
  await userEvent.type(screen.getByLabelText(/^new pin$/i), '1234');
  await userEvent.type(screen.getByLabelText(/confirm pin/i), '1234');
  await userEvent.click(screen.getByRole('button', { name: /create pin/i }));
  await waitFor(() => expect(screen.getByText('APP_CONTENT')).toBeInTheDocument());
});

test('shows unlock screen when stored device exists', () => {
  localStorage.setItem('cg_device', JSON.stringify({ email: 'user@demo.com', pinHash: 'hash:1234' }));
  renderGate();
  expect(screen.getByRole('heading', { name: /welcome back/i })).toBeInTheDocument();
  expect(screen.getByText('user@demo.com')).toBeInTheDocument();
});

test('correct PIN on unlock shows app content', async () => {
  localStorage.setItem('cg_device', JSON.stringify({ email: 'user@demo.com', pinHash: 'hash:1234' }));
  renderGate();
  await userEvent.type(screen.getByLabelText(/enter your pin/i), '1234');
  await userEvent.click(screen.getByRole('button', { name: /unlock/i }));
  await waitFor(() => expect(screen.getByText('APP_CONTENT')).toBeInTheDocument());
});

test('incorrect PIN shows error and clears input', async () => {
  localStorage.setItem('cg_device', JSON.stringify({ email: 'user@demo.com', pinHash: 'hash:1234' }));
  renderGate();
  await userEvent.type(screen.getByLabelText(/enter your pin/i), '9999');
  await userEvent.click(screen.getByRole('button', { name: /unlock/i }));
  await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Incorrect PIN'));
  const input = screen.getByLabelText(/enter your pin/i) as HTMLInputElement;
  expect(input.value).toBe('');
});

test('"Not you? Sign out" clears device and shows registration', async () => {
  localStorage.setItem('cg_device', JSON.stringify({ email: 'user@demo.com', pinHash: 'hash:1234' }));
  renderGate();
  await userEvent.click(screen.getByRole('button', { name: /not you/i }));
  expect(screen.getByRole('heading', { name: /create your pin/i })).toBeInTheDocument();
  expect(localStorage.getItem('cg_device')).toBeNull();
});

test('Nav logout keeps device and next render shows unlock', async () => {
  localStorage.setItem('cg_device', JSON.stringify({ email: 'user@demo.com', pinHash: 'hash:1234' }));
  const store = makeStore();
  store.dispatch(login());
  renderGate(store);
  expect(screen.getByText('APP_CONTENT')).toBeInTheDocument();
  store.dispatch({ type: 'auth/logout' });
  await waitFor(() =>
    expect(screen.getByRole('heading', { name: /welcome back/i })).toBeInTheDocument(),
  );
  expect(localStorage.getItem('cg_device')).not.toBeNull();
});
