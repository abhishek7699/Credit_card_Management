import { useState } from 'react';
import { useAddChargeMutation } from './bookingApi';
import { alertError, btnPrimary, fieldError, input, sectionTitle, stateText } from '../../components/ui';
import type { Booking, ChargeType } from '../../types/staff';

const CHARGE_TYPES: ChargeType[] = ['ROOM_SERVICE', 'MINIBAR', 'LAUNDRY', 'OTHER'];

interface ChargesPanelProps {
  booking: Booking;
}

/** US1/US2: add a charge to a CHECKED_IN booking and see the running balance —
 *  the add-charge mutation invalidates the Booking cache tag (bookingApi.ts), so
 *  the list and balance below update immediately, no poll wait (SC-001). */
export function ChargesPanel({ booking }: ChargesPanelProps) {
  const [addCharge, { isLoading, isError }] = useAddChargeMutation();
  const [type, setType] = useState<ChargeType>('ROOM_SERVICE');
  const [description, setDescription] = useState('');
  const [amount, setAmount] = useState('');
  const [fieldErrorText, setFieldErrorText] = useState<string | null>(null);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!description.trim()) {
      setFieldErrorText('Enter a description, for example "Club sandwich".');
      return;
    }
    const parsedAmount = Number(amount);
    if (!amount || Number.isNaN(parsedAmount) || parsedAmount <= 0) {
      setFieldErrorText('Enter an amount greater than 0.');
      return;
    }
    setFieldErrorText(null);
    try {
      await addCharge({ bookingId: booking.bookingId, type, description, amount: parsedAmount }).unwrap();
      setDescription('');
      setAmount('');
    } catch {
      // isError is managed by RTK Query; the entered fields stay so staff can retry.
    }
  }

  return (
    <section className="mt-6 space-y-3 border-t border-gray-200 pt-5">
      <h3 className={sectionTitle}>Charges</h3>

      {booking.charges.length === 0 ? (
        <p className={stateText}>No charges yet.</p>
      ) : (
        <ul className="space-y-1">
          {booking.charges.map((charge) => (
            <li key={charge.chargeId} className="flex justify-between text-base text-gray-800">
              <span>{charge.description}</span>
              <span>${charge.amount.toFixed(2)}</span>
            </li>
          ))}
        </ul>
      )}

      <p className="text-base font-semibold text-gray-900">Balance due: ${booking.balanceDue.toFixed(2)}</p>

      {isError && <div role="alert" className={alertError}>The charge wasn&apos;t added. Check it and tap Add Charge again.</div>}

      <form onSubmit={handleSubmit} noValidate className="space-y-3">
        <div className="grid grid-cols-2 gap-3">
          <select
            aria-label="Charge type"
            value={type}
            onChange={(e) => setType(e.target.value as ChargeType)}
            className={input}
          >
            {CHARGE_TYPES.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
          <input
            aria-label="Amount"
            type="text"
            inputMode="decimal"
            placeholder="Amount"
            value={amount}
            onChange={(e) => setAmount(e.target.value)}
            className={input}
          />
        </div>
        <input
          aria-label="Description"
          type="text"
          placeholder="Description"
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          className={input}
        />
        {fieldErrorText && <p className={fieldError}>{fieldErrorText}</p>}
        <button type="submit" disabled={isLoading} className={btnPrimary}>
          {isLoading ? 'Adding…' : 'Add Charge'}
        </button>
      </form>
    </section>
  );
}
