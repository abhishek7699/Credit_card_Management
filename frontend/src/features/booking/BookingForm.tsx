import { useState } from 'react';
import { useCreateBookingMutation } from './bookingApi';
import { validateBookingForm, type BookingFormErrors, type BookingFormState } from './validation';
import type { Booking } from '../../types/staff';
import { alertError, alertSuccess, btnPrimary, fieldError, fieldLabel, hint, input } from '../../components/ui';

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
  return <p className={fieldError}>{message}</p>;
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
      <div className={alertSuccess}>
        <p>Booking confirmed for {confirmed.guestName}.</p>
        <p className="mt-1">Balance due at checkout: ${confirmed.balanceDue.toFixed(2)}</p>
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
        <div role="alert" className={alertError}>
          The booking didn&apos;t go through. Check the details and tap Submit Booking to try again.
        </div>
      )}

      <div>
        <label htmlFor="guestName" className={fieldLabel}>
          Guest Name
        </label>
        <input
          id="guestName"
          type="text"
          value={values.guestName}
          onChange={(e) => handleChange('guestName', e.target.value)}
          className={input}
        />
        <FieldError message={errors.guestName} />
      </div>

      <div>
        <label htmlFor="guestPhone" className={fieldLabel}>
          Phone
        </label>
        <input
          id="guestPhone"
          type="tel"
          value={values.guestPhone}
          onChange={(e) => handleChange('guestPhone', e.target.value)}
          className={input}
        />
        <FieldError message={errors.guestPhone} />
      </div>

      <div>
        <label htmlFor="guestEmail" className={fieldLabel}>
          Email <span className={hint}>(optional)</span>
        </label>
        <input
          id="guestEmail"
          type="email"
          value={values.guestEmail}
          onChange={(e) => handleChange('guestEmail', e.target.value)}
          className={input}
        />
      </div>

      <div>
        <label htmlFor="visitingFrom" className={fieldLabel}>
          Visiting From <span className={hint}>(optional)</span>
        </label>
        <input
          id="visitingFrom"
          type="text"
          value={values.visitingFrom}
          onChange={(e) => handleChange('visitingFrom', e.target.value)}
          className={input}
        />
      </div>

      <div className="grid grid-cols-2 gap-4">
        <div>
          <label htmlFor="adults" className={fieldLabel}>
            Adults <span className={hint}>(default 1)</span>
          </label>
          <input
            id="adults"
            type="number"
            min={1}
            inputMode="numeric"
            value={values.adults}
            onChange={(e) => handleChange('adults', e.target.value)}
            className={input}
          />
          <FieldError message={errors.adults} />
        </div>
        <div>
          <label htmlFor="children" className={fieldLabel}>
            Children <span className={hint}>(default 0)</span>
          </label>
          <input
            id="children"
            type="number"
            min={0}
            inputMode="numeric"
            value={values.children}
            onChange={(e) => handleChange('children', e.target.value)}
            className={input}
          />
          <FieldError message={errors.children} />
        </div>
      </div>

      <div className="grid grid-cols-2 gap-4">
        <div>
          <label htmlFor="checkIn" className={fieldLabel}>
            Check-in
          </label>
          <input
            id="checkIn"
            type="date"
            value={values.checkIn}
            onChange={(e) => handleChange('checkIn', e.target.value)}
            className={input}
          />
          <FieldError message={errors.checkIn} />
        </div>
        <div>
          <label htmlFor="checkOut" className={fieldLabel}>
            Check-out
          </label>
          <input
            id="checkOut"
            type="date"
            value={values.checkOut}
            onChange={(e) => handleChange('checkOut', e.target.value)}
            className={input}
          />
          <FieldError message={errors.checkOut} />
        </div>
      </div>

      <div className="grid grid-cols-2 gap-4">
        <div>
          <label htmlFor="expectedArrivalTime" className={fieldLabel}>
            Expected Arrival <span className={hint}>(optional)</span>
          </label>
          <input
            id="expectedArrivalTime"
            type="time"
            value={values.expectedArrivalTime}
            onChange={(e) => handleChange('expectedArrivalTime', e.target.value)}
            className={input}
          />
        </div>
        <div>
          <label htmlFor="expectedDepartureTime" className={fieldLabel}>
            Expected Departure <span className={hint}>(optional)</span>
          </label>
          <input
            id="expectedDepartureTime"
            type="time"
            value={values.expectedDepartureTime}
            onChange={(e) => handleChange('expectedDepartureTime', e.target.value)}
            className={input}
          />
        </div>
      </div>

      <div>
        <label htmlFor="notes" className={fieldLabel}>
          Notes <span className={hint}>(optional)</span>
        </label>
        <textarea
          id="notes"
          value={values.notes}
          onChange={(e) => handleChange('notes', e.target.value)}
          className={input}
        />
      </div>

      <button
        type="submit"
        disabled={isLoading}
        className={btnPrimary}
      >
        {isLoading ? 'Submitting…' : 'Submit Booking'}
      </button>
    </form>
  );
}
