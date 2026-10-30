import type { RoomBoardEntry, RoomStatus } from '../../types/staff';

const STATUS_COLOR: Record<RoomStatus, string> = {
  VACANT_CLEAN: 'border-green-300 bg-green-100 text-green-900',
  OCCUPIED: 'border-red-300 bg-red-100 text-red-900',
  DIRTY: 'border-amber-300 bg-amber-100 text-amber-900',
  MAINTENANCE: 'border-gray-400 bg-gray-200 text-gray-700',
};

const STATUS_LABEL: Record<RoomStatus, string> = {
  VACANT_CLEAN: 'Vacant',
  OCCUPIED: 'Occupied',
  DIRTY: 'Dirty',
  MAINTENANCE: 'Maintenance',
};

interface RoomTileProps {
  room: RoomBoardEntry;
  onClick: (room: RoomBoardEntry) => void;
}

export function RoomTile({ room, onClick }: RoomTileProps) {
  return (
    <button
      type="button"
      onClick={() => onClick(room)}
      data-testid={`room-${room.roomNumber}`}
      data-status={room.status}
      className={`flex flex-col items-start gap-0.5 rounded border p-3 text-left text-sm ${STATUS_COLOR[room.status]}`}
    >
      <span className="font-semibold">{room.roomNumber}</span>
      <span>{STATUS_LABEL[room.status]}</span>
      {room.status === 'OCCUPIED' && room.guestName && <span className="text-xs">{room.guestName}</span>}
    </button>
  );
}
