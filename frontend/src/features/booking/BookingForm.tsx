import { useState } from 'react';
import { useCreateBookingMutation } from './bookingApi';
import { validateBookingForm, type BookingFormErrors, type BookingFormState } from './validation';
import type { Booking } from '../../types/staff';

const INITIAL: BookingFormState = {
  guestName: '',
  guestPhone: '',
  checkIn: '',
  checkOut: '',
  guestEmail: '',
  visitingFrom: '',
  adults: '',
  children: '',
  expectedArrivalTime: '',
  expectedDepartureTime: '',
  notes: '',
};

function FieldError({ message }: { message: string | undefined }) {
  if (!message) return null;
  return <p className="mt-1 text-sm text-red-600">{message}</p>;
}

interface BookingFormProps {
  roomId: string;
}

/** FR-017/FR-020: no card, of any kind, at this step -- the booking is confirmed
 *  synchronously, so a successful submit shows the confirmation directly from the
 *  mutation's own response (the full Booking), not an intermediate "awaiting
 *  payment" screen to poll. */
export function BookingForm({ roomId }: BookingFormProps) {
  const [values, setValues] = useState<BookingFormState>(INITIAL);
  const [errors, setErrors] = useState<BookingFormErrors>({});
  const [createBooking, { isLoading, isError }] = useCreateBookingMutation();
  const [confirmed, setConfirmed] = useState<Booking | null>(null);

  if (confirmed) {
    return (
      <div className="rounded border border-green-200 bg-green-50 p-4">
        <p className="text-sm text-green-800">Booking confirmed for {confirmed.guestName}.</p>
        <p className="mt-1 text-sm text-green-800">Balance due at checkout: ${confirmed.balanceDue.toFixed(2)}</p>
      </div>
    );
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
        guestPhone: values.guestPhone,
        checkIn: values.checkIn,
        checkOut: values.checkOut,
        ...(values.guestEmail.trim() && { guestEmail: values.guestEmail.trim() }),
        ...(values.visitingFrom.trim() && { visitingFrom: values.visitingFrom.trim() }),
        ...(values.adults.trim() && { adults: Number(values.adults) }),
        ...(values.children.trim() && { children: Number(values.children) }),
        ...(values.expectedArrivalTime && { expectedArrivalTime: values.expectedArrivalTime }),
        ...(values.expectedDepartureTime && { expectedDepartureTime: values.expectedDepartureTime }),
        ...(values.notes.trim() && { notes: values.notes.trim() }),
      }).unwrap();
      setConfirmed(result);
    } catch {
      // isError is managed by RTK Query.
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

      <div>
        <label htmlFor="guestPhone" className="block text-sm font-medium text-gray-700">
          Phone
        </label>
        <input
          id="guestPhone"
          type="tel"
          value={values.guestPhone}
          onChange={(e) => handleChange('guestPhone', e.target.value)}
          className="mt-1 w-full rounded border border-gray-300 px-3 py-2 text-sm"
        />
        <FieldError message={errors.guestPhone} />
      </div>

      <div>
        <label htmlFor="guestEmail" className="block text-sm font-medium text-gray-700">
          Email <span className="text-gray-400">(optional)</span>
        </label>
        <input
          id="guestEmail"
          type="email"
          value={values.guestEmail}
          onChange={(e) => handleChange('guestEmail', e.target.value)}
          className="mt-1 w-full rounded border border-gray-300 px-3 py-2 text-sm"
        />
      </div>

      <div>
        <label htmlFor="visitingFrom" className="block text-sm font-medium text-gray-700">
          Visiting From <span className="text-gray-400">(optional)</span>
        </label>
        <input
          id="visitingFrom"
          type="text"
          value={values.visitingFrom}
          onChange={(e) => handleChange('visitingFrom', e.target.value)}
          className="mt-1 w-full rounded border border-gray-300 px-3 py-2 text-sm"
        />
      </div>

      <div className="grid grid-cols-2 gap-4">
        <div>
          <label htmlFor="adults" className="block text-sm font-medium text-gray-700">
            Adults <span className="text-gray-400">(default 1)</span>
          </label>
          <input
            id="adults"
            type="number"
            min={1}
            inputMode="numeric"
            value={values.adults}
            onChange={(e) => handleChange('adults', e.target.value)}
            className="mt-1 w-full rounded border border-gray-300 px-3 py-2 text-sm"
          />
          <FieldError message={errors.adults} />
        </div>
        <div>
          <label htmlFor="children" className="block text-sm font-medium text-gray-700">
            Children <span className="text-gray-400">(default 0)</span>
          </label>
          <input
            id="children"
            type="number"
            min={0}
            inputMode="numeric"
            value={values.children}
            onChange={(e) => handleChange('children', e.target.value)}
            className="mt-1 w-full rounded border border-gray-300 px-3 py-2 text-sm"
          />
          <FieldError message={errors.children} />
        </div>
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

      <div className="grid grid-cols-2 gap-4">
        <div>
          <label htmlFor="expectedArrivalTime" className="block text-sm font-medium text-gray-700">
            Expected Arrival <span className="text-gray-400">(optional)</span>
          </label>
          <input
            id="expectedArrivalTime"
            type="time"
            value={values.expectedArrivalTime}
            onChange={(e) => handleChange('expectedArrivalTime', e.target.value)}
            className="mt-1 w-full rounded border border-gray-300 px-3 py-2 text-sm"
          />
        </div>
        <div>
          <label htmlFor="expectedDepartureTime" className="block text-sm font-medium text-gray-700">
            Expected Departure <span className="text-gray-400">(optional)</span>
          </label>
          <input
            id="expectedDepartureTime"
            type="time"
            value={values.expectedDepartureTime}
            onChange={(e) => handleChange('expectedDepartureTime', e.target.value)}
            className="mt-1 w-full rounded border border-gray-300 px-3 py-2 text-sm"
          />
        </div>
      </div>

      <div>
        <label htmlFor="notes" className="block text-sm font-medium text-gray-700">
          Notes <span className="text-gray-400">(optional)</span>
        </label>
        <textarea
          id="notes"
          value={values.notes}
          onChange={(e) => handleChange('notes', e.target.value)}
          className="mt-1 w-full rounded border border-gray-300 px-3 py-2 text-sm"
        />
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
