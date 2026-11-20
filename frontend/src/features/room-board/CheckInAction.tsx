import { useState } from 'react';
import { useCheckInMutation } from '../booking/bookingApi';
import { alertError, alertSuccess, btnPrimary, stateText } from '../../components/ui';

interface CheckInActionProps {
  bookingId: string;
  guestName: string | null;
}

export function CheckInAction({ bookingId, guestName }: CheckInActionProps) {
  const [checkIn, { isLoading, isError }] = useCheckInMutation();
  const [done, setDone] = useState(false);

  if (done) {
    return <p className={alertSuccess}>Checked in.</p>;
  }

  async function handleClick() {
    try {
      await checkIn(bookingId).unwrap();
      setDone(true);
    } catch {
      // isError is managed by RTK Query; the board's next poll corrects the
      // displayed status either way.
    }
  }

  return (
    <div className="space-y-4">
      <p className={stateText}>
        {guestName ? `${guestName} is arriving today.` : 'A guest is arriving today.'}
      </p>
      {isError && <div role="alert" className={alertError}>Check-in didn&apos;t go through. Tap Check In to try again.</div>}
      <button type="button" onClick={handleClick} disabled={isLoading} className={btnPrimary}>
        {isLoading ? 'Checking in…' : 'Check In'}
      </button>
    </div>
  );
}
