import { useCheckOutMutation } from '../booking/bookingApi';
import { CheckOutCardForm } from '../booking/CheckOutCardForm';
import type { BookingStatus, CardDetails } from '../../types/staff';

interface CheckOutActionProps {
  bookingId: string;
  guestName: string | null;
  status: BookingStatus;
  balanceDue: number;
}

/** US3/US4: a zero balance checks out immediately with the existing plain button;
 *  a balance greater than zero requires a card (CheckOutCardForm) instead. Once
 *  submitted, status/balanceDue (polled by the caller via useGetBookingQuery, which
 *  keeps polling while CHECKOUT_PENDING) drive what's shown next — not local
 *  component state — so a declined card correctly falls back to the card form
 *  again rather than getting stuck on a one-shot "done" flag. */
export function CheckOutAction({ bookingId, guestName, status, balanceDue }: CheckOutActionProps) {
  const [checkOut, { isLoading, isError }] = useCheckOutMutation();

  if (status === 'CHECKED_OUT') {
    return <p className="text-sm text-green-700">Checked out.</p>;
  }

  if (status === 'CHECKOUT_PENDING') {
    return <p className="text-sm text-gray-700">Processing checkout…</p>;
  }

  async function handleNoBalanceClick() {
    try {
      await checkOut({ bookingId }).unwrap();
    } catch {
      // isError is managed by RTK Query; the booking-detail poll corrects the
      // displayed status either way.
    }
  }

  async function handleCardSubmit(card: CardDetails) {
    try {
      await checkOut({ bookingId, card }).unwrap();
    } catch {
      // A declined/failed outcome surfaces through isError and the next poll
      // (back to CHECKED_IN, same balance, card form shown again), not a thrown
      // promise here.
    }
  }

  if (balanceDue > 0) {
    return (
      <div>
        <p className="mb-3 text-sm text-gray-700">
          {guestName ?? 'This guest'} has a balance due of ${balanceDue.toFixed(2)}.
        </p>
        {isError && (
          <div role="alert" className="mb-3 rounded border border-red-300 bg-red-50 p-3 text-sm text-red-700">
            Checkout was declined or failed. Please try a different card.
          </div>
        )}
        <CheckOutCardForm onSubmit={handleCardSubmit} isSubmitting={isLoading} />
      </div>
    );
  }

  return (
    <div>
      <p className="mb-3 text-sm text-gray-700">{guestName ?? 'This guest'} is currently occupying this room.</p>
      {isError && (
        <div role="alert" className="mb-3 rounded border border-red-300 bg-red-50 p-3 text-sm text-red-700">
          Check-out failed. Please try again.
        </div>
      )}
      <button
        type="button"
        onClick={handleNoBalanceClick}
        disabled={isLoading}
        className="w-full rounded bg-blue-600 px-3 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
      >
        {isLoading ? 'Checking out…' : 'Check Out'}
      </button>
    </div>
  );
}
