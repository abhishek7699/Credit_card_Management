import { useCheckOutMutation } from '../booking/bookingApi';
import { CheckOutCardForm } from '../booking/CheckOutCardForm';
import { alertError, alertSuccess, btnPrimary, stateText } from '../../components/ui';
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
    return <p className={alertSuccess}>Checked out.</p>;
  }

  if (status === 'CHECKOUT_PENDING') {
    return <p className={stateText}>Processing checkout… this can take a few seconds.</p>;
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
      <div className="space-y-4">
        <p className={stateText}>
          {guestName ?? 'This guest'} has a balance due of ${balanceDue.toFixed(2)}.
        </p>
        {isError && (
          <div role="alert" className={alertError}>
            Checkout was declined or failed. Please try a different card.
          </div>
        )}
        <CheckOutCardForm onSubmit={handleCardSubmit} isSubmitting={isLoading} />
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <p className={stateText}>{guestName ?? 'This guest'} is currently occupying this room.</p>
      {isError && <div role="alert" className={alertError}>Check-out didn&apos;t go through. Tap Check Out to try again.</div>}
      <button type="button" onClick={handleNoBalanceClick} disabled={isLoading} className={btnPrimary}>
        {isLoading ? 'Checking out…' : 'Check Out'}
      </button>
    </div>
  );
}
