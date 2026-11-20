import type { RoomBoardEntry } from '../../types/staff';
import { ROOM_STATUS_STYLE } from './roomStatusStyle';

interface RoomTileProps {
  room: RoomBoardEntry;
  onClick: (room: RoomBoardEntry) => void;
}

/** A whole room is one large button (min 96px tall) so it is easy to hit on a tablet. */
export function RoomTile({ room, onClick }: RoomTileProps) {
  const style = ROOM_STATUS_STYLE[room.status];
  return (
    <button
      type="button"
      onClick={() => onClick(room)}
      data-testid={`room-${room.roomNumber}`}
      data-status={room.status}
      className={`flex min-h-24 flex-col items-start gap-1 rounded-lg border-2 p-3 text-left text-base shadow-sm hover:brightness-95 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-600 ${style.tile}`}
    >
      <span className="text-lg font-bold">{room.roomNumber}</span>
      <span className="font-medium">{style.label}</span>
      {room.status === 'OCCUPIED' && room.guestName && <span className="text-sm">{room.guestName}</span>}
      {(room.bookingSource === 'ONLINE' || room.arrivingBookingSource === 'ONLINE') && (
        <span
          className="rounded bg-sky-600 px-2 py-0.5 text-sm font-semibold text-white"
          data-testid="online-badge"
        >
          ONLINE
        </span>
      )}
      {room.hasBalanceDue && (
        <span
          className="mt-auto rounded bg-amber-500 px-2 py-0.5 text-sm font-semibold text-white"
          data-testid="balance-due-badge"
        >
          Balance due
        </span>
      )}
    </button>
  );
}
