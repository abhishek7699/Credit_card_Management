import { useState } from 'react';
import { validateCardFields, type CardFormErrors, type CardFormState } from './validation';
import type { CardDetails } from '../../types/staff';

const INITIAL: CardFormState = {
  cardNumber: '',
  expiry: '',
  cvv: '',
  cardholderName: '',
};

function FieldError({ message }: { message: string | undefined }) {
  if (!message) return null;
  return <p className="mt-1 text-sm text-red-600">{message}</p>;
}

interface CheckOutCardFormProps {
  onSubmit: (card: CardDetails) => void;
  isSubmitting: boolean;
}

/** Card entry for checking out a balance due (US3) — same fields and validation
 *  rules as BookingForm's own card section (validation.ts's shared
 *  validateCardFields), re-entered here rather than reused from booking since
 *  the card is never stored (FR-008). */
export function CheckOutCardForm({ onSubmit, isSubmitting }: CheckOutCardFormProps) {
  const [values, setValues] = useState<CardFormState>(INITIAL);
  const [errors, setErrors] = useState<CardFormErrors>({});

  function handleChange(field: keyof CardFormState, value: string) {
    setValues((v) => ({ ...v, [field]: value }));
    setErrors((e) => {
      const next = { ...e };
      delete next[field];
      return next;
    });
  }

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    const validationErrors = validateCardFields(values);
    if (Object.keys(validationErrors).length > 0) {
      setErrors(validationErrors);
      return;
    }
    onSubmit({
      cardNumber: values.cardNumber.replace(/\s+/g, ''),
      expiry: values.expiry,
      cvv: values.cvv,
      cardholderName: values.cardholderName,
    });
    setValues(INITIAL);
  }

  return (
    <form onSubmit={handleSubmit} noValidate className="space-y-4">
      <div>
        <label htmlFor="checkout-cardNumber" className="block text-sm font-medium text-gray-700">
          Card Number
        </label>
        <input
          id="checkout-cardNumber"
          type="text"
          inputMode="numeric"
          value={values.cardNumber}
          onChange={(e) => handleChange('cardNumber', e.target.value)}
          className="mt-1 w-full rounded border border-gray-300 px-3 py-2 text-sm"
        />
        <FieldError message={errors.cardNumber} />
      </div>

      <div className="grid grid-cols-2 gap-4">
        <div>
          <label htmlFor="checkout-expiry" className="block text-sm font-medium text-gray-700">
            Expiry
          </label>
          <input
            id="checkout-expiry"
            type="text"
            placeholder="MM/YY"
            value={values.expiry}
            onChange={(e) => handleChange('expiry', e.target.value)}
            className="mt-1 w-full rounded border border-gray-300 px-3 py-2 text-sm"
          />
          <FieldError message={errors.expiry} />
        </div>
        <div>
          <label htmlFor="checkout-cvv" className="block text-sm font-medium text-gray-700">
            CVV
          </label>
          <input
            id="checkout-cvv"
            type="text"
            inputMode="numeric"
            value={values.cvv}
            onChange={(e) => handleChange('cvv', e.target.value)}
            className="mt-1 w-full rounded border border-gray-300 px-3 py-2 text-sm"
          />
          <FieldError message={errors.cvv} />
        </div>
      </div>

      <div>
        <label htmlFor="checkout-cardholderName" className="block text-sm font-medium text-gray-700">
          Cardholder Name
        </label>
        <input
          id="checkout-cardholderName"
          type="text"
          value={values.cardholderName}
          onChange={(e) => handleChange('cardholderName', e.target.value)}
          className="mt-1 w-full rounded border border-gray-300 px-3 py-2 text-sm"
        />
        <FieldError message={errors.cardholderName} />
      </div>

      <button
        type="submit"
        disabled={isSubmitting}
        className="w-full rounded bg-blue-600 px-3 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
      >
        {isSubmitting ? 'Processing…' : 'Charge and Check Out'}
      </button>
    </form>
  );
}
