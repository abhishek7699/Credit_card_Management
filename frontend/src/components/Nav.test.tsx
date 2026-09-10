import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { Provider } from 'react-redux';
import { store } from '../store';
import { Nav } from './Nav';

function renderNav(path = '/') {
  return render(
    <Provider store={store}>
      <MemoryRouter initialEntries={[path]}>
        <Nav />
      </MemoryRouter>
    </Provider>,
  );
}

test('renders Home link', () => {
  renderNav();
  expect(screen.getByRole('link', { name: 'Home' })).toBeInTheDocument();
});

test('renders New Payment link', () => {
  renderNav();
  expect(screen.getByRole('link', { name: 'New Payment' })).toBeInTheDocument();
});

test('renders Transaction History link', () => {
  renderNav();
  expect(screen.getByRole('link', { name: 'Transaction History' })).toBeInTheDocument();
});

test('New Payment link points to /payment', () => {
  renderNav();
  expect(screen.getByRole('link', { name: 'New Payment' })).toHaveAttribute('href', '/payment');
});

test('Transaction History link points to /history', () => {
  renderNav();
  expect(screen.getByRole('link', { name: 'Transaction History' })).toHaveAttribute('href', '/history');
});

test('active link has blue colour class when on /payment', () => {
  renderNav('/payment');
  const activeLink = screen.getByRole('link', { name: 'New Payment' });
  expect(activeLink.className).toContain('text-blue-600');
});

test('renders Log out button', () => {
  renderNav();
  expect(screen.getByRole('button', { name: /log out/i })).toBeInTheDocument();
});
