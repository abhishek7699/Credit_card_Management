import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useSubmitTransactionMutation } from './paymentApi';
import { validatePaymentForm } from './validation';
import type { FormErrors, FormValues } from './validation';

const INITIAL: FormValues = {
  cardNumber: '',
  expiry: '',
  cvv: '',
  cardholderName: '',
  amount: '',
  currency: 'USD',
};

function FieldError({ message }: { message: string | undefined }) {
  if (!message) return null;
  return <p className="mt-1 text-sm text-red-600">{message}</p>;
}

export default function PaymentForm() {
  const navigate = useNavigate();
  const [values, setValues] = useState<FormValues>(INITIAL);
  const [errors, setErrors] = useState<FormErrors>({});
  const [submitTransaction, { isLoading, isError }] = useSubmitTransactionMutation();

  function handleChange(field: keyof FormValues, value: string) {
    setValues((v) => ({ ...v, [field]: value }));
    setErrors((e) => {
      const next = { ...e };
      delete next[field];
      return next;
    });
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    const validationErrors = validatePaymentForm(values);
    if (Object.keys(validationErrors).length > 0) {
      setErrors(validationErrors);
      return;
    }
    try {
      const result = await submitTransaction({
        cardNumber: values.cardNumber.replace(/\s+/g, ''),
        expiry: values.expiry,
        cvv: values.cvv,
        cardholderName: values.cardholderName,
        amount: parseFloat(values.amount),
        currency: values.currency,
      }).unwrap();
      navigate(`/transactions/${result.id}`);
    } catch {
      // isError is managed by RTK Query
    }
  }

  const apiErrorMessage = isError ? 'Payment submission failed. Please try again.' : null;

  return (
    <main className="mx-auto max-w-lg px-4 py-10">
      <h1 className="mb-6 text-2xl font-bold text-gray-900">New Payment</h1>

      {apiErrorMessage && (
        <div role="alert" className="mb-4 rounded border border-red-300 bg-red-50 p-3 text-sm text-red-700">
          {apiErrorMessage}
        </div>
      )}

      <form onSubmit={handleSubmit} noValidate className="space-y-4">
        <div>
          <label htmlFor="cardNumber" className="block text-sm font-medium text-gray-700">
            Card Number
          </label>
          <input
            id="cardNumber"
            type="text"
            inputMode="numeric"
            placeholder="1234 5678 9012 3456"
            value={values.cardNumber}
            onChange={(e) => handleChange('cardNumber', e.target.value)}
            className="mt-1 w-full rounded border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none"
          />
          <FieldError message={errors.cardNumber} />
        </div>

        <div className="grid grid-cols-2 gap-4">
          <div>
            <label htmlFor="expiry" className="block text-sm font-medium text-gray-700">
              Expiry
            </label>
            <input
              id="expiry"
              type="text"
              placeholder="MM/YY"
              value={values.expiry}
              onChange={(e) => handleChange('expiry', e.target.value)}
              className="mt-1 w-full rounded border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none"
            />
            <FieldError message={errors.expiry} />
          </div>
          <div>
            <label htmlFor="cvv" className="block text-sm font-medium text-gray-700">
              CVV
            </label>
            <input
              id="cvv"
              type="text"
              inputMode="numeric"
              placeholder="123"
              value={values.cvv}
              onChange={(e) => handleChange('cvv', e.target.value)}
              className="mt-1 w-full rounded border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none"
            />
            <FieldError message={errors.cvv} />
          </div>
        </div>

        <div>
          <label htmlFor="cardholderName" className="block text-sm font-medium text-gray-700">
            Cardholder Name
          </label>
          <input
            id="cardholderName"
            type="text"
            placeholder="Jane Doe"
            value={values.cardholderName}
            onChange={(e) => handleChange('cardholderName', e.target.value)}
            className="mt-1 w-full rounded border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none"
          />
          <FieldError message={errors.cardholderName} />
        </div>

        <div className="grid grid-cols-3 gap-4">
          <div className="col-span-2">
            <label htmlFor="amount" className="block text-sm font-medium text-gray-700">
              Amount
            </label>
            <input
              id="amount"
              type="number"
              min="0.01"
              step="0.01"
              placeholder="0.00"
              value={values.amount}
              onChange={(e) => handleChange('amount', e.target.value)}
              className="mt-1 w-full rounded border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none"
            />
            <FieldError message={errors.amount} />
          </div>
          <div>
            <label htmlFor="currency" className="block text-sm font-medium text-gray-700">
              Currency
            </label>
            <select
              id="currency"
              value={values.currency}
              onChange={(e) => handleChange('currency', e.target.value)}
              className="mt-1 w-full rounded border border-gray-300 px-3 py-2 text-sm focus:border-blue-500 focus:outline-none"
            >
              <option value="USD">USD</option>
              <option value="EUR">EUR</option>
              <option value="GBP">GBP</option>
            </select>
          </div>
        </div>

        <button
          type="submit"
          disabled={isLoading}
          className="mt-2 w-full rounded bg-blue-600 px-4 py-2 font-medium text-white hover:bg-blue-700 disabled:opacity-60"
        >
          {isLoading ? 'Processing…' : 'Submit Payment'}
        </button>
      </form>
    </main>
  );
}
