import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { Provider } from 'react-redux';
import { beforeEach, expect, test } from 'vitest';
import { server } from './mocks/server';
import { store } from './store';
import { login, logout } from './features/staff-session/staffSessionSlice';
import App from './App';

function renderAt(path: string) {
  window.history.pushState({}, '', path);
  return render(
    <Provider store={store}>
      <App />
    </Provider>,
  );
}

beforeEach(() => {
  store.dispatch(logout());
});

test('/ redirects to /staff and shows the staff login screen', async () => {
  renderAt('/');
  expect(await screen.findByText(/staff sign in/i)).toBeInTheDocument();
});

test('an unknown path also redirects to /staff', async () => {
  renderAt('/dashboard');
  expect(await screen.findByText(/staff sign in/i)).toBeInTheDocument();
});

test('/staff shows the staff login screen when signed out', async () => {
  renderAt('/staff');
  expect(await screen.findByText(/staff sign in/i)).toBeInTheDocument();
});

test('once authenticated, /staff shows the shell (Room Board link, Sign out) around the board', async () => {
  server.use(http.get('*/api/v1/rooms/board', () => HttpResponse.json([])));
  store.dispatch(login({ role: 'FRONT_DESK', token: 'tok-app-test' }));

  renderAt('/staff');

  expect(await screen.findByRole('link', { name: 'Room Board' })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Sign out' })).toBeInTheDocument();
  expect(await screen.findByRole('heading', { name: 'Room Board' })).toBeInTheDocument();
});

test('Sign out ends the session and shows the login screen again', async () => {
  server.use(http.get('*/api/v1/rooms/board', () => HttpResponse.json([])));
  store.dispatch(login({ role: 'FRONT_DESK', token: 'tok-app-test-2' }));

  renderAt('/staff');
  await screen.findByRole('button', { name: 'Sign out' });
  await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));

  expect(await screen.findByText(/staff sign in/i)).toBeInTheDocument();
});
