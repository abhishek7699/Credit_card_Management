import type { GuestDetails as GuestDetailsType } from '../../types/staff';

interface GuestDetailsProps {
  guest: GuestDetailsType;
}

/** FR-023: the full guest record recorded at booking, shown from the same room
 *  panel used for charges/checkout AND from the arriving-booking panel before
 *  check-in -- staff see it in both states, not only once a guest is checked in
 *  (Clarifications: "existing panel extended... applies to both"). */
export function GuestDetails({ guest }: GuestDetailsProps) {
  return (
    <dl className="mb-4 grid grid-cols-2 gap-x-3 gap-y-1 text-sm text-gray-700">
      <div className="col-span-2">
        <dt className="inline font-medium text-gray-900">{guest.guestName}</dt>
      </div>
      <div>
        <dt className="text-gray-500">Phone</dt>
        <dd>{guest.guestPhone}</dd>
      </div>
      {guest.guestEmail && (
        <div>
          <dt className="text-gray-500">Email</dt>
          <dd>{guest.guestEmail}</dd>
        </div>
      )}
      {guest.visitingFrom && (
        <div>
          <dt className="text-gray-500">Visiting from</dt>
          <dd>{guest.visitingFrom}</dd>
        </div>
      )}
      <div>
        <dt className="text-gray-500">Party</dt>
        <dd>
          {guest.adults} adult{guest.adults === 1 ? '' : 's'}
          {guest.children > 0 ? `, ${guest.children} child${guest.children === 1 ? '' : 'ren'}` : ''}
        </dd>
      </div>
      {guest.expectedArrivalTime && (
        <div>
          <dt className="text-gray-500">Expected arrival</dt>
          <dd>{guest.expectedArrivalTime}</dd>
        </div>
      )}
      {guest.expectedDepartureTime && (
        <div>
          <dt className="text-gray-500">Expected departure</dt>
          <dd>{guest.expectedDepartureTime}</dd>
        </div>
      )}
      {guest.notes && (
        <div className="col-span-2">
          <dt className="text-gray-500">Notes</dt>
          <dd>{guest.notes}</dd>
        </div>
      )}
    </dl>
  );
}
