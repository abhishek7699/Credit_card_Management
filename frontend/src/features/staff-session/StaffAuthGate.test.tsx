import { render, screen } from '@testing-library/react';
import { Provider } from 'react-redux';
import { beforeEach, expect, test } from 'vitest';
import { store } from '../../store';
import { login, logout, sessionExpired } from './staffSessionSlice';
import { StaffAuthGate } from './StaffAuthGate';

function renderGate() {
  return render(
    <Provider store={store}>
      <StaffAuthGate>
        <p>Protected content</p>
      </StaffAuthGate>
    </Provider>,
  );
}

beforeEach(() => {
  store.dispatch(logout());
});

test('renders the login screen when anonymous', () => {
  renderGate();
  expect(screen.getByText(/staff sign in/i)).toBeInTheDocument();
  expect(screen.queryByText('Protected content')).not.toBeInTheDocument();
});

test('renders the login screen when expired', () => {
  store.dispatch(login({ role: 'FRONT_DESK', token: 'tok' }));
  store.dispatch(sessionExpired());
  renderGate();
  expect(screen.getByText(/staff sign in/i)).toBeInTheDocument();
  expect(screen.queryByText('Protected content')).not.toBeInTheDocument();
});

test('renders children when authenticated', () => {
  store.dispatch(login({ role: 'FRONT_DESK', token: 'tok' }));
  renderGate();
  expect(screen.getByText('Protected content')).toBeInTheDocument();
  expect(screen.queryByText(/staff sign in/i)).not.toBeInTheDocument();
});
