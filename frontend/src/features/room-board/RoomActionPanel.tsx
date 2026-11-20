import type { ReactNode } from 'react';
import { useGetBookingQuery } from '../booking/bookingApi';
import { BookingForm } from '../booking/BookingForm';
import { ChargesPanel } from '../booking/ChargesPanel';
import { GuestDetails } from '../booking/GuestDetails';
import { alertError, stateText } from '../../components/ui';
import { CheckInAction } from './CheckInAction';
import { CheckOutAction } from './CheckOutAction';
import { StatusChangeAction } from './StatusChangeAction';
import type { Booking, RoomBoardEntry, StaffRole } from '../../types/staff';

interface RoomActionPanelProps {
  room: RoomBoardEntry;
  role: StaffRole;
  onClose: () => void;
}

/** Loads one booking and handles its loading and failure states, so the panels below
 *  only ever see a real booking. */
function BookingLoader({
  bookingId,
  children,
}: {
  bookingId: string;
  children: (booking: Booking) => ReactNode;
}) {
  const { data: booking, isLoading, isError, refetch } = useGetBookingQuery(bookingId);

  if (isError && !booking) {
    return (
      <div role="alert" className={alertError}>
        <p>We couldn&apos;t load this booking.</p>
        <button
          type="button"
          onClick={refetch}
          className="mt-3 min-h-12 w-full rounded-lg border border-red-400 bg-white px-4 font-semibold text-red-900 hover:bg-red-100"
        >
          Try again
        </button>
      </div>
    );
  }

  if (isLoading || !booking) {
    return <p className={stateText}>Loading guest details…</p>;
  }

  return <>{children(booking)}</>;
}

/** US1/US3/FR-023: the occupied room's booking drives check-out, charges and the guest block. */
function OccupiedRoomPanel({ bookingId, guestName }: { bookingId: string; guestName: string | null }) {
  return (
    <BookingLoader bookingId={bookingId}>
      {(booking) => (
        <>
          <GuestDetails guest={booking} />
          <CheckOutAction
            bookingId={bookingId}
            guestName={guestName}
            status={booking.status}
            balanceDue={booking.balanceDue}
          />
          <ChargesPanel booking={booking} />
        </>
      )}
    </BookingLoader>
  );
}

/** FR-023 (Clarifications): the arriving panel shows the full guest record before check-in. */
function ArrivingRoomPanel({ bookingId }: { bookingId: string }) {
  return (
    <BookingLoader bookingId={bookingId}>
      {(booking) => (
        <>
          <GuestDetails guest={booking} />
          <CheckInAction bookingId={bookingId} guestName={booking.guestName} />
        </>
      )}
    </BookingLoader>
  );
}

function renderAction(room: RoomBoardEntry, role: StaffRole) {
  // An OCCUPIED room takes priority over everything else: check-in can't
  // actually succeed against it yet (the backend requires VACANT_CLEAN),
  // regardless of whether a different guest's booking also happens to be
  // arriving later today (same-day turnover) — check-out is the action that
  // applies right now.
  if (room.currentBookingId && role === 'FRONT_DESK') {
    return <OccupiedRoomPanel bookingId={room.currentBookingId} guestName={room.guestName} />;
  }
  // A room awaiting arrival takes priority over "book a new reservation" even
  // if it's still VACANT_CLEAN (which it is, until check-in happens).
  if (room.arrivingBookingId && role === 'FRONT_DESK') {
    return <ArrivingRoomPanel bookingId={room.arrivingBookingId} />;
  }
  if (room.status === 'VACANT_CLEAN' && role === 'FRONT_DESK') {
    return <BookingForm roomId={room.roomId} />;
  }
  // DIRTY/MAINTENANCE status changes are offered to either role (FR-018) —
  // unlike booking/check-in/check-out above, which stay front-desk only.
  if (room.status === 'DIRTY' || room.status === 'MAINTENANCE') {
    return <StatusChangeAction roomId={room.roomId} status={room.status} />;
  }
  return <p className={stateText}>Nothing to do for this room right now.</p>;
}

/** Side panel for one room. Full screen on a phone, a 384px column on a tablet. */
export function RoomActionPanel({ room, role, onClose }: RoomActionPanelProps) {
  return (
    <>
      <div className="fixed inset-0 z-30 bg-black/30" onClick={onClose} aria-hidden="true" />
      <div
        role="dialog"
        aria-label={`Room ${room.roomNumber} actions`}
        className="fixed inset-y-0 right-0 z-40 w-full overflow-y-auto border-l border-gray-200 bg-white p-5 shadow-xl sm:w-96"
      >
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-xl font-bold text-gray-900">Room {room.roomNumber}</h2>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close panel"
            className="flex h-12 w-12 items-center justify-center rounded-lg text-xl text-gray-600 hover:bg-gray-100 hover:text-gray-900"
          >
            ✕
          </button>
        </div>
        {renderAction(room, role)}
      </div>
    </>
  );
}
