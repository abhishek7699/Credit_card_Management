import type { ReactElement } from 'react';
import { render } from '@testing-library/react';
import { Provider } from 'react-redux';
import { MemoryRouter } from 'react-router-dom';
import { store } from '../store';

export function renderWithProviders(
  ui: ReactElement,
  { initialEntries = ['/'] }: { initialEntries?: string[] } = {},
) {
  return render(
    <Provider store={store}>
      <MemoryRouter initialEntries={initialEntries}>{ui}</MemoryRouter>
    </Provider>,
  );
}
