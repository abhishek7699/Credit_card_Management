import { useState } from 'react';
import { useCheckInMutation } from '../booking/bookingApi';

interface CheckInActionProps {
  bookingId: string;
  guestName: string | null;
}

export function CheckInAction({ bookingId, guestName }: CheckInActionProps) {
  const [checkIn, { isLoading, isError }] = useCheckInMutation();
  const [done, setDone] = useState(false);

  if (done) {
    return <p className="text-sm text-green-700">Checked in.</p>;
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
    <div>
      <p className="mb-3 text-sm text-gray-700">
        {guestName ? `${guestName} is arriving today.` : 'A guest is arriving today.'}
      </p>
      {isError && (
        <div role="alert" className="mb-3 rounded border border-red-300 bg-red-50 p-3 text-sm text-red-700">
          Check-in failed. Please try again.
        </div>
      )}
      <button
        type="button"
        onClick={handleClick}
        disabled={isLoading}
        className="w-full rounded bg-blue-600 px-3 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
      >
        {isLoading ? 'Checking in…' : 'Check In'}
      </button>
    </div>
  );
}
