import type { OfferRoom } from './types';
import { formatMoney, nightsLabel } from './format';

interface AvailableRoomsProps {
  rooms: OfferRoom[];
  nights: number;
  onSelect: (room: OfferRoom) => void;
}

/** The rooms free for the searched dates, each with its nightly rate, its total for the
 *  stay (both from the server), and what the room includes. */
export function AvailableRooms({ rooms, nights, onSelect }: AvailableRoomsProps) {
  if (rooms.length === 0) {
    return <p className="text-slate-700">No rooms are free for those dates. Try different dates.</p>;
  }
  return (
    <section aria-label="Available rooms" className="space-y-3">
      <h2 className="text-xl font-semibold">Available rooms</h2>
      <ul className="space-y-3">
        {rooms.map((room) => (
          <li
            key={room.roomId}
            data-testid={`offer-${room.roomNumber}`}
            className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm"
          >
            <div className="flex items-baseline justify-between gap-2">
              <h3 className="text-lg font-semibold">
                Room {room.roomNumber} <span className="text-sm font-normal text-slate-600">{room.roomType}</span>
              </h3>
              <p className="text-right">
                <span className="font-semibold">{formatMoney(room.nightlyRate)}</span>
                <span className="text-sm text-slate-600"> per night</span>
              </p>
            </div>
            <p className="mt-1 text-sm text-slate-700">
              Total for {nightsLabel(nights)}: <span className="font-semibold">{formatMoney(room.total)}</span>
            </p>
            {room.included.length > 0 && (
              <ul aria-label={`Included in room ${room.roomNumber}`} className="mt-2 flex flex-wrap gap-2 text-sm">
                {room.included.map((item) => (
                  <li key={item} className="rounded bg-emerald-50 px-2 py-0.5 text-emerald-800">
                    {item}
                  </li>
                ))}
              </ul>
            )}
            <button
              type="button"
              onClick={() => onSelect(room)}
              className="mt-3 rounded bg-indigo-600 px-4 py-2 font-medium text-white hover:bg-indigo-700"
            >
              Book room {room.roomNumber}
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}
