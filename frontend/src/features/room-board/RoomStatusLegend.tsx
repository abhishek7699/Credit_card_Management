import { ROOM_STATUS_ORDER, ROOM_STATUS_STYLE } from './roomStatusStyle';

/** Small key above the board: what each room colour means. */
export function RoomStatusLegend() {
  return (
    <ul aria-label="Room colour key" className="mb-4 flex flex-wrap gap-x-5 gap-y-2 text-base text-gray-700">
      {ROOM_STATUS_ORDER.map((status) => (
        <li key={status} className="flex items-center gap-2">
          <span aria-hidden="true" className={`h-4 w-4 rounded-sm ${ROOM_STATUS_STYLE[status].swatch}`} />
          {ROOM_STATUS_STYLE[status].label}
        </li>
      ))}
    </ul>
  );
}
