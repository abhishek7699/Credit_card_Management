import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Provider } from 'react-redux';
import { beforeEach, expect, test, vi } from 'vitest';
import { store } from '../../store';
import { StatusChangeAction } from './StatusChangeAction';

const mockTrigger = vi.fn();
const mockMutationState: { isLoading: boolean; isError: boolean } = {
  isLoading: false,
  isError: false,
};

vi.mock('./roomStatusApi', async () => {
  const actual = await vi.importActual<typeof import('./roomStatusApi')>('./roomStatusApi');
  return {
    ...actual,
    useUpdateRoomStatusMutation: () => [mockTrigger, mockMutationState],
  };
});

function renderAction(status: 'DIRTY' | 'MAINTENANCE') {
  return render(
    <Provider store={store}>
      <StatusChangeAction roomId="r1" status={status} />
    </Provider>,
  );
}

beforeEach(() => {
  mockMutationState.isLoading = false;
  mockMutationState.isError = false;
  mockTrigger.mockReset();
  mockTrigger.mockReturnValue({ unwrap: () => Promise.resolve({ roomId: 'r1', status: 'VACANT_CLEAN' }) });
});

test('a DIRTY room offers both "Mark Clean" and "Mark Maintenance"', () => {
  renderAction('DIRTY');
  expect(screen.getByRole('button', { name: /mark clean/i })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: /mark maintenance/i })).toBeInTheDocument();
});

test('a MAINTENANCE room offers only "Mark Clean"', () => {
  renderAction('MAINTENANCE');
  expect(screen.getByRole('button', { name: /mark clean/i })).toBeInTheDocument();
  expect(screen.queryByRole('button', { name: /mark maintenance/i })).not.toBeInTheDocument();
});

test('clicking "Mark Clean" calls the mutation with VACANT_CLEAN, then shows confirmation', async () => {
  renderAction('DIRTY');
  await userEvent.click(screen.getByRole('button', { name: /mark clean/i }));

  expect(mockTrigger).toHaveBeenCalledWith({ roomId: 'r1', status: 'VACANT_CLEAN' });
  expect(await screen.findByText(/status updated/i)).toBeInTheDocument();
});

test('clicking "Mark Maintenance" calls the mutation with MAINTENANCE', async () => {
  renderAction('DIRTY');
  await userEvent.click(screen.getByRole('button', { name: /mark maintenance/i }));

  expect(mockTrigger).toHaveBeenCalledWith({ roomId: 'r1', status: 'MAINTENANCE' });
});

test('a failed update shows an inline error and keeps the buttons available', async () => {
  mockTrigger.mockReturnValue({ unwrap: () => Promise.reject(new Error('conflict')) });
  mockMutationState.isError = true;
  renderAction('DIRTY');
  await userEvent.click(screen.getByRole('button', { name: /mark clean/i }));

  expect(screen.getByRole('alert')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: /mark clean/i })).toBeInTheDocument();
});

test('buttons are disabled while a request is in flight', () => {
  mockMutationState.isLoading = true;
  renderAction('DIRTY');
  expect(screen.getByRole('button', { name: /mark clean/i })).toBeDisabled();
  expect(screen.getByRole('button', { name: /mark maintenance/i })).toBeDisabled();
});
