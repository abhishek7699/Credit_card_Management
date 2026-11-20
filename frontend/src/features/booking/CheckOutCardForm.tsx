import { useState } from 'react';
import { validateCardFields, type CardFormErrors, type CardFormState } from './validation';
import type { CardDetails } from '../../types/staff';
import { btnPrimary, fieldError, fieldLabel, input } from '../../components/ui';

const INITIAL: CardFormState = {
  cardNumber: '',
  expiry: '',
  cvv: '',
  cardholderName: '',
};

function FieldError({ message }: { message: string | undefined }) {
  if (!message) return null;
  return <p className={fieldError}>{message}</p>;
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
        <label htmlFor="checkout-cardNumber" className={fieldLabel}>
          Card Number
        </label>
        <input
          id="checkout-cardNumber"
          type="text"
          inputMode="numeric"
          value={values.cardNumber}
          onChange={(e) => handleChange('cardNumber', e.target.value)}
          className={input}
        />
        <FieldError message={errors.cardNumber} />
      </div>

      <div className="grid grid-cols-2 gap-4">
        <div>
          <label htmlFor="checkout-expiry" className={fieldLabel}>
            Expiry
          </label>
          <input
            id="checkout-expiry"
            type="text"
            placeholder="MM/YY"
            value={values.expiry}
            onChange={(e) => handleChange('expiry', e.target.value)}
            className={input}
          />
          <FieldError message={errors.expiry} />
        </div>
        <div>
          <label htmlFor="checkout-cvv" className={fieldLabel}>
            CVV
          </label>
          <input
            id="checkout-cvv"
            type="text"
            inputMode="numeric"
            value={values.cvv}
            onChange={(e) => handleChange('cvv', e.target.value)}
            className={input}
          />
          <FieldError message={errors.cvv} />
        </div>
      </div>

      <div>
        <label htmlFor="checkout-cardholderName" className={fieldLabel}>
          Cardholder Name
        </label>
        <input
          id="checkout-cardholderName"
          type="text"
          value={values.cardholderName}
          onChange={(e) => handleChange('cardholderName', e.target.value)}
          className={input}
        />
        <FieldError message={errors.cardholderName} />
      </div>

      <button
        type="submit"
        disabled={isSubmitting}
        className={btnPrimary}
      >
        {isSubmitting ? 'Processing…' : 'Charge and Check Out'}
      </button>
    </form>
  );
}
