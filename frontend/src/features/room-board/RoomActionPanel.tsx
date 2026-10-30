import { BookingForm } from '../booking/BookingForm';
import { CheckInAction } from './CheckInAction';
import type { RoomBoardEntry, StaffRole } from '../../types/staff';

interface RoomActionPanelProps {
  room: RoomBoardEntry;
  role: StaffRole;
  onClose: () => void;
}

function renderAction(room: RoomBoardEntry, role: StaffRole) {
  // A room awaiting arrival takes priority over "book a new reservation" even
  // if it's still VACANT_CLEAN (which it is, until check-in happens) — check-in
  // is the action staff actually need for a room that already has a booking
  // arriving today.
  if (room.arrivingBookingId && role === 'FRONT_DESK') {
    return <CheckInAction bookingId={room.arrivingBookingId} guestName={room.arrivingGuestName} />;
  }
  if (room.status === 'VACANT_CLEAN' && role === 'FRONT_DESK') {
    return <BookingForm roomId={room.roomId} />;
  }
  return <p className="text-sm text-gray-500">No action available for this room.</p>;
}

export function RoomActionPanel({ room, role, onClose }: RoomActionPanelProps) {
  return (
    <>
      <div className="fixed inset-0 z-30 bg-black/20" onClick={onClose} aria-hidden="true" />
      <div
        role="dialog"
        aria-label={`Room ${room.roomNumber} actions`}
        className="fixed inset-y-0 right-0 z-40 w-96 overflow-y-auto border-l border-gray-200 bg-white p-5 shadow-xl"
      >
        <div className="mb-4 flex items-center justify-between">
          <h2 className="font-semibold text-gray-900">Room {room.roomNumber}</h2>
          <button type="button" onClick={onClose} aria-label="Close panel" className="text-gray-400 hover:text-gray-600">
            ✕
          </button>
        </div>
        {renderAction(room, role)}
      </div>
    </>
  );
}
