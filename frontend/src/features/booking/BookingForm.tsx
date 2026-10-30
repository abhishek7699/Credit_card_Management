import { useState } from 'react';
import { useCreateBookingMutation } from './bookingApi';
import { BookingStatusView } from './BookingStatusView';
import { validateBookingForm, type BookingFormErrors, type BookingFormState } from './validation';

const INITIAL: BookingFormState = {
  guestName: '',
  checkIn: '',
  checkOut: '',
  cardNumber: '',
  expiry: '',
  cvv: '',
  cardholderName: '',
};

function FieldError({ message }: { message: string | undefined }) {
  if (!message) return null;
  return <p className="mt-1 text-sm text-red-600">{message}</p>;
}

interface BookingFormProps {
  roomId: string;
}

export function BookingForm({ roomId }: BookingFormProps) {
  const [values, setValues] = useState<BookingFormState>(INITIAL);
  const [errors, setErrors] = useState<BookingFormErrors>({});
  const [createBooking, { isLoading, isError }] = useCreateBookingMutation();
  const [bookingId, setBookingId] = useState<string | null>(null);

  // Once a booking is submitted, this component shows its progress instead of
  // the form — the card fields above are never rendered again (discarded, not
  // merely hidden, per FR-013).
  if (bookingId) {
    return <BookingStatusView bookingId={bookingId} />;
  }

  function handleChange(field: keyof BookingFormState, value: string) {
    setValues((v) => ({ ...v, [field]: value }));
    setErrors((e) => {
      const next = { ...e };
      delete next[field];
      return next;
    });
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    const validationErrors = validateBookingForm(values);
    if (Object.keys(validationErrors).length > 0) {
      setErrors(validationErrors);
      return;
    }
    try {
      const result = await createBooking({
        roomId,
        guestName: values.guestName,
        checkIn: values.checkIn,
        checkOut: values.checkOut,
        card: {
          cardNumber: values.cardNumber.replace(/\s+/g, ''),
          expiry: values.expiry,
          cvv: values.cvv,
          cardholderName: values.cardholderName,
        },
      }).unwrap();
      setBookingId(result.bookingId);
    } catch {
      // isError is managed by RTK Query. Per FR-013/the spec's own edge case,
      // a failed submission never retains the entered card details — staff
      // must re-enter them to try again.
      setValues(INITIAL);
    }
  }

  return (
    <form onSubmit={handleSubmit} noValidate className="space-y-4">
      {isError && (
        <div role="alert" className="rounded border border-red-300 bg-red-50 p-3 text-sm text-red-700">
          Booking submission failed. Please try again.
        </div>
      )}

      <div>
        <label htmlFor="guestName" className="block text-sm font-medium text-gray-700">
          Guest Name
        </label>
        <input
          id="guestName"
          type="text"
          value={values.guestName}
          onChange={(e) => handleChange('guestName', e.target.value)}
          className="mt-1 w-full rounded border border-gray-300 px-3 py-2 text-sm"
        />
        <FieldError message={errors.guestName} />
      </div>

      <div className="grid grid-cols-2 gap-4">
        <div>
          <label htmlFor="checkIn" className="block text-sm font-medium text-gray-700">
            Check-in
          </label>
          <input
            id="checkIn"
            type="date"
            value={values.checkIn}
            onChange={(e) => handleChange('checkIn', e.target.value)}
            className="mt-1 w-full rounded border border-gray-300 px-3 py-2 text-sm"
          />
          <FieldError message={errors.checkIn} />
        </div>
        <div>
          <label htmlFor="checkOut" className="block text-sm font-medium text-gray-700">
            Check-out
          </label>
          <input
            id="checkOut"
            type="date"
            value={values.checkOut}
            onChange={(e) => handleChange('checkOut', e.target.value)}
            className="mt-1 w-full rounded border border-gray-300 px-3 py-2 text-sm"
          />
          <FieldError message={errors.checkOut} />
        </div>
      </div>

      <div>
        <label htmlFor="cardNumber" className="block text-sm font-medium text-gray-700">
          Card Number
        </label>
        <input
          id="cardNumber"
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
          <label htmlFor="expiry" className="block text-sm font-medium text-gray-700">
            Expiry
          </label>
          <input
            id="expiry"
            type="text"
            placeholder="MM/YY"
            value={values.expiry}
            onChange={(e) => handleChange('expiry', e.target.value)}
            className="mt-1 w-full rounded border border-gray-300 px-3 py-2 text-sm"
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
            value={values.cvv}
            onChange={(e) => handleChange('cvv', e.target.value)}
            className="mt-1 w-full rounded border border-gray-300 px-3 py-2 text-sm"
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
          value={values.cardholderName}
          onChange={(e) => handleChange('cardholderName', e.target.value)}
          className="mt-1 w-full rounded border border-gray-300 px-3 py-2 text-sm"
        />
        <FieldError message={errors.cardholderName} />
      </div>

      <button
        type="submit"
        disabled={isLoading}
        className="w-full rounded bg-blue-600 px-3 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
      >
        {isLoading ? 'Submitting…' : 'Submit Booking'}
      </button>
    </form>
  );
}
