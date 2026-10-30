import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { RetryBanner } from './RetryBanner';

test('renders the message and calls onRetry when clicked', async () => {
  const onRetry = vi.fn();
  render(<RetryBanner onRetry={onRetry} />);
  expect(screen.getByRole('alert')).toBeInTheDocument();
  await userEvent.click(screen.getByRole('button', { name: /retry now/i }));
  expect(onRetry).toHaveBeenCalledTimes(1);
});
