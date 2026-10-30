import { render, screen } from '@testing-library/react';
import { Provider } from 'react-redux';
import { beforeEach, expect, test } from 'vitest';
import { store } from './store';
import { logout } from './features/staff-session/staffSessionSlice';
import App from './App';

vi.mock('./features/auth/hashPin', () => ({
  hashPin: (p: string) => Promise.resolve(`hash:${p}`),
}));

function renderAt(path: string) {
  window.history.pushState({}, '', path);
  return render(
    <Provider store={store}>
      <App />
    </Provider>,
  );
}

beforeEach(() => {
  localStorage.clear();
  store.dispatch(logout());
});

test('an existing route (/) still goes through the PIN AuthGate, unchanged', async () => {
  renderAt('/');
  expect(await screen.findByText(/create your pin/i)).toBeInTheDocument();
  expect(screen.queryByText(/staff sign in/i)).not.toBeInTheDocument();
});

test('an existing dashboard route still goes through the PIN AuthGate, unchanged', async () => {
  renderAt('/dashboard');
  expect(await screen.findByText(/create your pin/i)).toBeInTheDocument();
});

test('/staff shows the staff login screen, not the PIN flow', async () => {
  renderAt('/staff');
  expect(await screen.findByText(/staff sign in/i)).toBeInTheDocument();
  expect(screen.queryByText(/create your pin/i)).not.toBeInTheDocument();
});
