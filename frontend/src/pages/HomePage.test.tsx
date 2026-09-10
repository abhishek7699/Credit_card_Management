import { render, screen } from '@testing-library/react';
import { Provider } from 'react-redux';
import { MemoryRouter } from 'react-router-dom';
import { store } from '../store';
import HomePage from './HomePage';

test('renders home page heading', () => {
  render(
    <Provider store={store}>
      <MemoryRouter>
        <HomePage />
      </MemoryRouter>
    </Provider>,
  );
  expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument();
  expect(screen.getByText(/Credit Card Gateway/i)).toBeInTheDocument();
});
