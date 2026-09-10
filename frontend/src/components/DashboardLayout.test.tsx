import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { DashboardLayout } from './DashboardLayout';

function renderLayout(path = '/dashboard') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <DashboardLayout />
    </MemoryRouter>,
  );
}

test('renders property name monogram', () => {
  renderLayout();
  expect(screen.getByText('OB')).toBeInTheDocument();
});

test('renders property full name', () => {
  renderLayout();
  expect(screen.getByText('Oceanview Boutique')).toBeInTheDocument();
});

test('renders Home, Floor Plan, Payments nav links', () => {
  renderLayout();
  expect(screen.getByRole('link', { name: /home/i })).toBeInTheDocument();
  expect(screen.getByRole('link', { name: /floor plan/i })).toBeInTheDocument();
  expect(screen.getByRole('link', { name: /payments/i })).toBeInTheDocument();
});

test('renders Reservations and Rooms as active nav links', () => {
  renderLayout();
  expect(screen.getByRole('link', { name: /reservations/i })).toBeInTheDocument();
  expect(screen.getByRole('link', { name: /rooms/i })).toBeInTheDocument();
  expect(screen.getByRole('link', { name: /reservations/i })).toHaveAttribute('href', '/dashboard/reservations');
  expect(screen.getByRole('link', { name: /rooms/i })).toHaveAttribute('href', '/dashboard/rooms');
});

test('Home nav link points to /dashboard', () => {
  renderLayout();
  expect(screen.getByRole('link', { name: /home/i })).toHaveAttribute('href', '/dashboard');
});

test('Payments nav link points to /dashboard/payments', () => {
  renderLayout();
  expect(screen.getByRole('link', { name: /payments/i })).toHaveAttribute('href', '/dashboard/payments');
});
