import { useState, type FormEvent, type ReactNode } from 'react';
import type { OfferRoom, OnlineBookingRequest } from './types';
import { validateCardFields, type CardFormErrors, type CardFormState } from '../booking/validation';
import { validateGuestForm, type GuestFormErrors, type GuestFormValues } from './validation';

export type GuestDetails = Pick<OnlineBookingRequest, 'guestName' | 'guestPhone' | 'guestEmail' | 'adults' | 'children' | 'card'>;

interface GuestBookingFormProps {
  room: OfferRoom;
  onSubmit: (details: GuestDetails) => void;
  submitting: boolean;
  /** The server's message for a failed submit (already user-facing), if any. */
  serverError: string | null;
}

const EMPTY: GuestFormValues = { guestName: '', guestPhone: '', guestEmail: '', adults: '1', children: '0' };
const EMPTY_CARD: CardFormState = { cardNumber: '', expiry: '', cvv: '', cardholderName: '' };

export function GuestBookingForm({ room, onSubmit, submitting, serverError }: GuestBookingFormProps) {
  const [values, setValues] = useState<GuestFormValues>(EMPTY);
  const [errors, setErrors] = useState<GuestFormErrors>({});
  const [card, setCard] = useState<CardFormState>(EMPTY_CARD);
  const [cardErrors, setCardErrors] = useState<CardFormErrors>({});

  function update(field: keyof GuestFormValues, value: string) {
    setValues((prev) => ({ ...prev, [field]: value }));
  }

  function updateCard(field: keyof CardFormState, value: string) {
    setCard((prev) => ({ ...prev, [field]: value }));
  }

  function handleSubmit(event: FormEvent) {
    event.preventDefault();
    const found = validateGuestForm(values);
    const cardFound = validateCardFields(card);
    setErrors(found);
    setCardErrors(cardFound);
    if (Object.keys(found).length > 0 || Object.keys(cardFound).length > 0) return;
    onSubmit({
      guestName: values.guestName.trim(),
      guestPhone: values.guestPhone.trim(),
      guestEmail: values.guestEmail.trim() || null,
      adults: Number(values.adults),
      children: Number(values.children),
      card: {
        cardNumber: card.cardNumber.replace(/\s+/g, ''),
        expiry: card.expiry.trim(),
        cvv: card.cvv.trim(),
        cardholderName: card.cardholderName.trim(),
      },
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

      <fieldset className="space-y-4 rounded border border-slate-200 p-4">
        <legend className="px-1 text-sm font-semibold text-slate-800">Card to guarantee your booking</legend>
        <p className="text-sm text-slate-700">
          We check that your card is valid to hold this booking. Nothing is charged, and we never store your card
          number or security code.
        </p>
        <Field label="Card number" id="guest-card-number" error={cardErrors.cardNumber}>
          <input
            id="guest-card-number"
            className="rounded border border-slate-300 bg-white px-3 py-2"
            inputMode="numeric"
            value={card.cardNumber}
            onChange={(e) => updateCard('cardNumber', e.target.value)}
            autoComplete="cc-number"
          />
        </Field>
        <div className="grid grid-cols-2 gap-4">
          <Field label="Expiry (MM/YY)" id="guest-card-expiry" error={cardErrors.expiry}>
            <input
              id="guest-card-expiry"
              className="rounded border border-slate-300 bg-white px-3 py-2"
              placeholder="MM/YY"
              value={card.expiry}
              onChange={(e) => updateCard('expiry', e.target.value)}
              autoComplete="cc-exp"
            />
          </Field>
          <Field label="CVV" id="guest-card-cvv" error={cardErrors.cvv}>
            <input
              id="guest-card-cvv"
              className="rounded border border-slate-300 bg-white px-3 py-2"
              inputMode="numeric"
              value={card.cvv}
              onChange={(e) => updateCard('cvv', e.target.value)}
              autoComplete="cc-csc"
            />
          </Field>
        </div>
        <Field label="Name on card" id="guest-card-name" error={cardErrors.cardholderName}>
          <input
            id="guest-card-name"
            className="rounded border border-slate-300 bg-white px-3 py-2"
            value={card.cardholderName}
            onChange={(e) => updateCard('cardholderName', e.target.value)}
            autoComplete="cc-name"
          />
        </Field>
      </fieldset>

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
