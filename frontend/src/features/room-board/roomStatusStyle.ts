import type { RoomStatus } from '../../types/staff';

/**
 * One color per room status, used by both the tiles and the legend, so the two can't drift.
 * Each tile also prints the status word, so colour is never the only cue.
 *   Vacant (clean) — green: ready to book or check in
 *   Occupied       — blue: a guest is in the room
 *   Dirty          — amber: needs housekeeping
 *   Maintenance    — red: out of service
 */
export const ROOM_STATUS_STYLE: Record<RoomStatus, { tile: string; swatch: string; label: string }> = {
  VACANT_CLEAN: {
    tile: 'border-emerald-400 bg-emerald-50 text-emerald-950',
    swatch: 'bg-emerald-400',
    label: 'Vacant',
  },
  OCCUPIED: {
    tile: 'border-sky-500 bg-sky-100 text-sky-950',
    swatch: 'bg-sky-500',
    label: 'Occupied',
  },
  DIRTY: {
    tile: 'border-amber-400 bg-amber-100 text-amber-950',
    swatch: 'bg-amber-400',
    label: 'Dirty',
  },
  MAINTENANCE: {
    tile: 'border-rose-500 bg-rose-100 text-rose-950',
    swatch: 'bg-rose-500',
    label: 'Maintenance',
  },
};

/** Order the legend and the tile's status word are listed in. */
export const ROOM_STATUS_ORDER: RoomStatus[] = ['VACANT_CLEAN', 'OCCUPIED', 'DIRTY', 'MAINTENANCE'];
