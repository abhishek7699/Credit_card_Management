import { Link, useLocation, useParams } from 'react-router-dom';
import { formatMoney, nightsLabel } from './format';
import type { OnlineBookingConfirmation } from './types';

/** /booked/:bookingId, after a guest's booking is confirmed. The booking reference is in the
 *  URL, so it survives a reload. The room details come from the navigation state, so they
 *  are shown only on the first view of this page. */
export default function BookingConfirmation() {
  const { bookingId } = useParams();
  const location = useLocation();
  const details = isConfirmation(location.state) ? location.state : null;

  return (
    <main className="mx-auto max-w-2xl space-y-6 p-4">
      <header className="space-y-1">
        <h1 className="text-3xl font-bold">Booking confirmed</h1>
        <p className="text-slate-700">Thank you. Your room is reserved.</p>
      </header>

      <section aria-label="Booking reference" className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
        <p className="text-sm text-slate-600">Booking reference</p>
        <p data-testid="booking-reference" className="break-all font-mono text-lg font-semibold">
          {bookingId}
        </p>
      </section>

      {details ? (
        <dl className="grid grid-cols-2 gap-2 rounded-lg border border-slate-200 bg-white p-4 text-sm">
          <dt className="text-slate-600">Room</dt>
          <dd>
            {details.roomNumber} ({details.roomType})
          </dd>
          <dt className="text-slate-600">Check-in</dt>
          <dd>{details.checkIn}</dd>
          <dt className="text-slate-600">Check-out</dt>
          <dd>{details.checkOut}</dd>
          <dt className="text-slate-600">Stay</dt>
          <dd>{nightsLabel(details.nights)}</dd>
          <dt className="text-slate-600">Total</dt>
          <dd className="font-semibold">{formatMoney(details.total)}</dd>
        </dl>
      ) : (
        <p className="text-slate-700">Keep your booking reference. The room details are not shown after a reload.</p>
      )}

      <p className="rounded bg-amber-50 p-3 text-amber-900">
        <span className="font-semibold">Pay at checkout.</span> Nothing is charged now. The room is settled when you
        check out.
      </p>

      <Link to="/" className="inline-block font-medium text-indigo-700 underline">
        Back to the front office
      </Link>
    </main>
  );
}

function isConfirmation(state: unknown): state is OnlineBookingConfirmation {
  return typeof state === 'object' && state !== null && 'bookingId' in state && 'roomNumber' in state;
}
