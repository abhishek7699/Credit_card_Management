import { useState } from 'react';
import { useAddChargeMutation } from './bookingApi';
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
  const [fieldError, setFieldError] = useState<string | null>(null);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (!description.trim()) {
      setFieldError('Description is required');
      return;
    }
    const parsedAmount = Number(amount);
    if (!amount || Number.isNaN(parsedAmount) || parsedAmount <= 0) {
      setFieldError('Enter an amount greater than 0');
      return;
    }
    setFieldError(null);
    try {
      await addCharge({ bookingId: booking.bookingId, type, description, amount: parsedAmount }).unwrap();
      setDescription('');
      setAmount('');
    } catch {
      // isError is managed by RTK Query; the entered fields stay so staff can retry.
    }
  }

  return (
    <div className="mt-4 border-t border-gray-200 pt-4">
      <h3 className="mb-2 text-sm font-semibold text-gray-900">Charges</h3>

      {booking.charges.length === 0 ? (
        <p className="mb-3 text-sm text-gray-500">No charges yet.</p>
      ) : (
        <ul className="mb-3 space-y-1">
          {booking.charges.map((charge) => (
            <li key={charge.chargeId} className="flex justify-between text-sm text-gray-700">
              <span>{charge.description}</span>
              <span>${charge.amount.toFixed(2)}</span>
            </li>
          ))}
        </ul>
      )}

      <p className="mb-3 text-sm font-semibold text-gray-900">Balance due: ${booking.balanceDue.toFixed(2)}</p>

      {isError && (
        <div role="alert" className="mb-3 rounded border border-red-300 bg-red-50 p-3 text-sm text-red-700">
          Failed to add charge. Please try again.
        </div>
      )}

      <form onSubmit={handleSubmit} noValidate className="space-y-2">
        <div className="grid grid-cols-2 gap-2">
          <select
            aria-label="Charge type"
            value={type}
            onChange={(e) => setType(e.target.value as ChargeType)}
            className="rounded border border-gray-300 px-2 py-1 text-sm"
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
            className="rounded border border-gray-300 px-2 py-1 text-sm"
          />
        </div>
        <input
          aria-label="Description"
          type="text"
          placeholder="Description"
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          className="w-full rounded border border-gray-300 px-2 py-1 text-sm"
        />
        {fieldError && <p className="text-sm text-red-600">{fieldError}</p>}
        <button
          type="submit"
          disabled={isLoading}
          className="w-full rounded bg-blue-600 px-3 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
        >
          {isLoading ? 'Adding…' : 'Add Charge'}
        </button>
      </form>
    </div>
  );
}
