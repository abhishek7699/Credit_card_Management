import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { Provider } from 'react-redux';
import { beforeEach, expect, test } from 'vitest';
import { store } from '../store';
import { login } from '../features/staff-session/staffSessionSlice';
import { StaffShell } from './StaffShell';

function renderShell(path = '/staff') {
  return render(
    <Provider store={store}>
      <MemoryRouter initialEntries={[path]}>
        <StaffShell>
          <p>Page content</p>
        </StaffShell>
      </MemoryRouter>
    </Provider>,
  );
}

beforeEach(() => {
  store.dispatch(login({ role: 'FRONT_DESK', token: 'tok-shell-test' }));
});

test('renders the app name, the Room Board link, Sign out, and the children', () => {
  renderShell();
  expect(screen.getByText('Hotel Payment App')).toBeInTheDocument();
  expect(screen.getByRole('link', { name: 'Room Board' })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Sign out' })).toBeInTheDocument();
  expect(screen.getByText('Page content')).toBeInTheDocument();
});

test('the Room Board link points at /staff and is marked active there', () => {
  renderShell('/staff');
  const link = screen.getByRole('link', { name: 'Room Board' });
  expect(link).toHaveAttribute('href', '/staff');
  expect(link).toHaveClass('text-indigo-700');
});

test('the Room Board link is not marked active on an unrelated path', () => {
  renderShell('/staff/somewhere-else');
  const link = screen.getByRole('link', { name: 'Room Board' });
  expect(link).not.toHaveClass('text-indigo-700');
});

test('clicking Sign out dispatches staffSession logout', async () => {
  renderShell();
  await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));
  expect(store.getState().staffSession.status).toBe('anonymous');
});
