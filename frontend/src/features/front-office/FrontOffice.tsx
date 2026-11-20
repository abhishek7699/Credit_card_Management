import { useMemo, useState, type FormEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import { skipToken } from '@reduxjs/toolkit/query';
import { useCreateOnlineBookingMutation, useGetAvailabilityQuery } from '../../store/publicApi';
import { AvailableRooms } from './AvailableRooms';
import { GuestBookingForm, type GuestDetails } from './GuestBookingForm';
import { errorMessage } from './apiError';
import { greetingFor } from './greeting';
import { localIsoDate } from './format';
import type { OfferRoom } from './types';

interface Dates {
  checkIn: string;
  checkOut: string;
}

/** The guest's front page at /: a greeting for their local time, a date search, the rooms
 *  free for those dates, and the booking form. Every price and rule comes from the server. */
export default function FrontOffice() {
  const navigate = useNavigate();
  const greeting = useMemo(() => greetingFor(new Date().getHours()), []);
  const today = useMemo(() => localIsoDate(new Date()), []);

  const [draft, setDraft] = useState<Dates>({ checkIn: '', checkOut: '' });
  const [searched, setSearched] = useState<Dates | null>(null);
  const [dateError, setDateError] = useState<string | null>(null);
  const [selected, setSelected] = useState<OfferRoom | null>(null);

  const availability = useGetAvailabilityQuery(searched ?? skipToken);
  const [createBooking, booking] = useCreateOnlineBookingMutation();

  function handleSearch(event: FormEvent) {
    event.preventDefault();
    if (!draft.checkIn || !draft.checkOut) {
      setDateError('Choose a check-in and a check-out date.');
      return;
    }
    if (draft.checkOut <= draft.checkIn) {
      setDateError('Check-out must be after check-in.');
      return;
    }
    setDateError(null);
    setSelected(null);
    setSearched(draft);
  }

  async function handleBook(details: GuestDetails) {
    if (!selected || !searched) return;
    try {
      const confirmation = await createBooking({
        roomId: selected.roomId,
        checkIn: searched.checkIn,
        checkOut: searched.checkOut,
        ...details,
      }).unwrap();
      navigate(`/booked/${confirmation.bookingId}`, { state: confirmation });
    } catch {
      // Shown below the form from booking.error; the guest stays on the page to retry.
    }
  }

  const searchError = dateError ?? (availability.error ? errorMessage(availability.error) : null);

  return (
    <main className="mx-auto max-w-3xl space-y-6 p-4">
      <header className="space-y-1">
        <h1 className="text-3xl font-bold">{greeting}. Welcome.</h1>
        <p className="text-slate-700">Choose your dates to see the rooms that are free.</p>
      </header>

      <form aria-label="Find a room" onSubmit={handleSearch} noValidate className="flex flex-wrap items-end gap-4">
        <label className="flex flex-col text-sm font-medium">
          Check-in
          <input
            type="date"
            name="checkIn"
            min={today}
            value={draft.checkIn}
            onChange={(e) => setDraft({ ...draft, checkIn: e.target.value })}
            className="rounded border border-slate-300 bg-white px-3 py-2"
          />
        </label>
        <label className="flex flex-col text-sm font-medium">
          Check-out
          <input
            type="date"
            name="checkOut"
            min={draft.checkIn || today}
            value={draft.checkOut}
            onChange={(e) => setDraft({ ...draft, checkOut: e.target.value })}
            className="rounded border border-slate-300 bg-white px-3 py-2"
          />
        </label>
        <button type="submit" className="rounded bg-indigo-600 px-4 py-2 font-medium text-white hover:bg-indigo-700">
          Search rooms
        </button>
      </form>

      {searchError && (
        <p role="alert" className="rounded bg-red-50 p-3 text-red-800">
          {searchError}
        </p>
      )}
      {searched && availability.isFetching && <p className="text-slate-600">Checking availability…</p>}
      {searched && availability.data && (
        <AvailableRooms rooms={availability.data.rooms} nights={availability.data.nights} onSelect={setSelected} />
      )}

      {selected && (
        <GuestBookingForm
          room={selected}
          onSubmit={handleBook}
          submitting={booking.isLoading}
          serverError={booking.error ? errorMessage(booking.error) : null}
        />
      )}
    </main>
  );
}
