import { useState, type FormEvent, type ReactNode } from 'react';
import type { OfferRoom, OnlineBookingRequest } from './types';
import { validateGuestForm, type GuestFormErrors, type GuestFormValues } from './validation';

export type GuestDetails = Pick<OnlineBookingRequest, 'guestName' | 'guestPhone' | 'guestEmail' | 'adults' | 'children'>;

interface GuestBookingFormProps {
  room: OfferRoom;
  onSubmit: (details: GuestDetails) => void;
  submitting: boolean;
  /** The server's message for a failed submit (already user-facing), if any. */
  serverError: string | null;
}

const EMPTY: GuestFormValues = { guestName: '', guestPhone: '', guestEmail: '', adults: '1', children: '0' };

export function GuestBookingForm({ room, onSubmit, submitting, serverError }: GuestBookingFormProps) {
  const [values, setValues] = useState<GuestFormValues>(EMPTY);
  const [errors, setErrors] = useState<GuestFormErrors>({});

  function update(field: keyof GuestFormValues, value: string) {
    setValues((prev) => ({ ...prev, [field]: value }));
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    const found = validateGuestForm(values);
    setErrors(found);
    if (Object.keys(found).length > 0) return;
    onSubmit({
      guestName: values.guestName.trim(),
      guestPhone: values.guestPhone.trim(),
      guestEmail: values.guestEmail.trim() || null,
      adults: Number(values.adults),
      children: Number(values.children),
    });
  }

  return (
    <form
      aria-label={`Your details for room ${room.roomNumber}`}
      onSubmit={handleSubmit}
      noValidate
      className="space-y-4 rounded-lg border border-slate-200 bg-white p-4 shadow-sm"
    >
      <h2 className="text-xl font-semibold">Your details</h2>
      <p className="text-sm text-slate-700">
        Room {room.roomNumber}. You pay at checkout, so nothing is charged now.
      </p>

      <Field label="Full name" id="guest-name" error={errors.guestName}>
        <input
          id="guest-name"
          className="rounded border border-slate-300 bg-white px-3 py-2"
          value={values.guestName}
          onChange={(e) => update('guestName', e.target.value)}
          autoComplete="name"
        />
      </Field>
      <Field label="Phone" id="guest-phone" error={errors.guestPhone}>
        <input
          id="guest-phone"
          className="rounded border border-slate-300 bg-white px-3 py-2"
          type="tel"
          value={values.guestPhone}
          onChange={(e) => update('guestPhone', e.target.value)}
          autoComplete="tel"
        />
      </Field>
      <Field label="Email (optional)" id="guest-email" error={errors.guestEmail}>
        <input
          id="guest-email"
          className="rounded border border-slate-300 bg-white px-3 py-2"
          type="email"
          value={values.guestEmail}
          onChange={(e) => update('guestEmail', e.target.value)}
          autoComplete="email"
        />
      </Field>
      <div className="grid grid-cols-2 gap-4">
        <Field label="Adults" id="guest-adults" error={errors.adults}>
          <input
            id="guest-adults"
            className="rounded border border-slate-300 bg-white px-3 py-2"
            type="number"
            min={1}
            value={values.adults}
            onChange={(e) => update('adults', e.target.value)}
          />
        </Field>
        <Field label="Children" id="guest-children" error={errors.children}>
          <input
            id="guest-children"
            className="rounded border border-slate-300 bg-white px-3 py-2"
            type="number"
            min={0}
            value={values.children}
            onChange={(e) => update('children', e.target.value)}
          />
        </Field>
      </div>

      {serverError && (
        <p role="alert" className="rounded bg-red-50 p-3 text-red-800">
          {serverError}
        </p>
      )}

      <button
        type="submit"
        disabled={submitting}
        className="rounded bg-indigo-600 px-4 py-2 font-medium text-white hover:bg-indigo-700 disabled:opacity-60"
      >
        {submitting ? 'Confirming…' : 'Confirm booking'}
      </button>
    </form>
  );
}

function Field({
  label,
  id,
  error,
  children,
}: {
  label: string;
  id: string;
  error?: string | undefined;
  children: ReactNode;
}) {
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="text-sm font-medium text-slate-800">
        {label}
      </label>
      {children}
      {error && <p className="text-sm text-red-700">{error}</p>}
    </div>
  );
}
