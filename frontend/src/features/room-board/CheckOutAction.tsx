import { useState } from 'react';
import { useCheckOutMutation } from '../booking/bookingApi';

interface CheckOutActionProps {
  bookingId: string;
  guestName: string | null;
}

export function CheckOutAction({ bookingId, guestName }: CheckOutActionProps) {
  const [checkOut, { isLoading, isError }] = useCheckOutMutation();
  const [done, setDone] = useState(false);

  if (done) {
    return <p className="text-sm text-green-700">Checked out.</p>;
  }

  async function handleClick() {
    try {
      await checkOut(bookingId).unwrap();
      setDone(true);
    } catch {
      // isError is managed by RTK Query; the board's next poll corrects the
      // displayed status either way.
    }
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
        onClick={handleClick}
        disabled={isLoading}
        className="w-full rounded bg-blue-600 px-3 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
      >
        {isLoading ? 'Checking out…' : 'Check Out'}
      </button>
    </div>
  );
}
