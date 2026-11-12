import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, test, vi } from 'vitest';
import { CheckOutCardForm } from './CheckOutCardForm';

test('submitting valid card details calls onSubmit with the exact payload, then clears the fields', async () => {
  const onSubmit = vi.fn();
  render(<CheckOutCardForm onSubmit={onSubmit} isSubmitting={false} />);

  await userEvent.type(screen.getByLabelText(/card number/i), '4111 1111 1111 1111');
  await userEvent.type(screen.getByLabelText(/expiry/i), '12/30');
  await userEvent.type(screen.getByLabelText(/cvv/i), '123');
  await userEvent.type(screen.getByLabelText(/cardholder name/i), 'Jane Doe');
  await userEvent.click(screen.getByRole('button', { name: /charge and check out/i }));

  expect(onSubmit).toHaveBeenCalledWith({
    cardNumber: '4111111111111111',
    expiry: '12/30',
    cvv: '123',
    cardholderName: 'Jane Doe',
  });
  expect(screen.getByLabelText(/card number/i)).toHaveValue('');
});

test('submitting with missing fields shows inline errors and never calls onSubmit', async () => {
  const onSubmit = vi.fn();
  render(<CheckOutCardForm onSubmit={onSubmit} isSubmitting={false} />);

  await userEvent.click(screen.getByRole('button', { name: /charge and check out/i }));

  expect(screen.getByText(/card number is required/i)).toBeInTheDocument();
  expect(onSubmit).not.toHaveBeenCalled();
});

test('correcting a field after an error clears just that field\'s error', async () => {
  const onSubmit = vi.fn();
  render(<CheckOutCardForm onSubmit={onSubmit} isSubmitting={false} />);

  await userEvent.click(screen.getByRole('button', { name: /charge and check out/i }));
  expect(screen.getByText(/card number is required/i)).toBeInTheDocument();

  await userEvent.type(screen.getByLabelText(/card number/i), '4111111111111111');
  expect(screen.queryByText(/card number is required/i)).not.toBeInTheDocument();
});

test('shows a processing label and disables submit while isSubmitting', () => {
  render(<CheckOutCardForm onSubmit={vi.fn()} isSubmitting={true} />);
  expect(screen.getByRole('button', { name: /processing/i })).toBeDisabled();
});
