import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { Provider } from 'react-redux';
import { store } from '../../store';
import { MOCK_TRANSACTION } from '../../mocks/mockData';
import PaymentForm from './PaymentForm';

const mockTrigger = vi.fn();
const mockMutationState: { isLoading: boolean; isError: boolean; error: unknown } = {
  isLoading: false,
  isError: false,
  error: undefined,
};

vi.mock('./paymentApi', () => ({
  useSubmitTransactionMutation: () => [mockTrigger, mockMutationState],
}));

function renderForm() {
  return render(
    <Provider store={store}>
      <MemoryRouter>
        <PaymentForm />
      </MemoryRouter>
    </Provider>,
  );
}

beforeEach(() => {
  mockMutationState.isLoading = false;
  mockMutationState.isError = false;
  mockMutationState.error = undefined;
  mockTrigger.mockReset();
  mockTrigger.mockReturnValue({ unwrap: () => Promise.resolve(MOCK_TRANSACTION) });
});

test('renders all form fields and submit button', () => {
  renderForm();
  expect(screen.getByLabelText(/card number/i)).toBeInTheDocument();
  expect(screen.getByLabelText(/expiry/i)).toBeInTheDocument();
  expect(screen.getByLabelText(/cvv/i)).toBeInTheDocument();
  expect(screen.getByLabelText(/cardholder name/i)).toBeInTheDocument();
  expect(screen.getByLabelText(/amount/i)).toBeInTheDocument();
  expect(screen.getByLabelText(/currency/i)).toBeInTheDocument();
  expect(screen.getByRole('button', { name: /submit payment/i })).toBeInTheDocument();
});

test('shows required-field errors on empty submit', async () => {
  renderForm();
  await userEvent.click(screen.getByRole('button', { name: /submit payment/i }));
  expect(screen.getByText(/card number is required/i)).toBeInTheDocument();
  expect(screen.getByText(/expiry date is required/i)).toBeInTheDocument();
  expect(screen.getByText(/cvv is required/i)).toBeInTheDocument();
  expect(screen.getByText(/cardholder name is required/i)).toBeInTheDocument();
  expect(screen.getByText(/amount is required/i)).toBeInTheDocument();
});

test('shows card number format error for too-short number', async () => {
  renderForm();
  await userEvent.type(screen.getByLabelText(/card number/i), '1234');
  await userEvent.click(screen.getByRole('button', { name: /submit payment/i }));
  expect(screen.getByText(/valid card number/i)).toBeInTheDocument();
});

test('shows amount error for zero', async () => {
  renderForm();
  await userEvent.type(screen.getByLabelText(/amount/i), '0');
  await userEvent.click(screen.getByRole('button', { name: /submit payment/i }));
  expect(screen.getByText(/amount must be greater than 0/i)).toBeInTheDocument();
});

test('shows amount error for negative value', async () => {
  renderForm();
  await userEvent.type(screen.getByLabelText(/amount/i), '-5');
  await userEvent.click(screen.getByRole('button', { name: /submit payment/i }));
  expect(screen.getByText(/amount must be greater than 0/i)).toBeInTheDocument();
});

test('calls mutation with correct payload on valid submit', async () => {
  renderForm();
  await userEvent.type(screen.getByLabelText(/card number/i), '4111111111111111');
  await userEvent.type(screen.getByLabelText(/expiry/i), '12/30');
  await userEvent.type(screen.getByLabelText(/cvv/i), '123');
  await userEvent.type(screen.getByLabelText(/cardholder name/i), 'Jane Doe');
  await userEvent.type(screen.getByLabelText(/amount/i), '99.99');
  await userEvent.click(screen.getByRole('button', { name: /submit payment/i }));
  expect(mockTrigger).toHaveBeenCalledWith(
    expect.objectContaining({ cardNumber: '4111111111111111', amount: 99.99 }),
  );
});

test('submit button is disabled and shows Processing while loading', () => {
  mockMutationState.isLoading = true;
  renderForm();
  const btn = screen.getByRole('button', { name: /processing/i });
  expect(btn).toBeDisabled();
});

test('shows error banner when API call fails', () => {
  mockMutationState.isError = true;
  mockMutationState.error = { status: 500, data: 'Internal server error' };
  renderForm();
  expect(screen.getByRole('alert')).toBeInTheDocument();
});
