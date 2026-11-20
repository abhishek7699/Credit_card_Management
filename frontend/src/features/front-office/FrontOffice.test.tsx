import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { Provider } from 'react-redux';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { server } from '../../mocks/server';
import { publicApi } from '../../store/publicApi';
import { store } from '../../store';
import BookingConfirmation from './BookingConfirmation';
import FrontOffice from './FrontOffice';

function renderFrontOffice() {
  return render(
    <Provider store={store}>
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<FrontOffice />} />
          <Route path="/booked/:bookingId" element={<BookingConfirmation />} />
        </Routes>
      </MemoryRouter>
    </Provider>,
  );
}

const ROOM_101 = {
  roomId: 'room-101',
  roomNumber: '101',
  roomType: 'STANDARD',
  nightlyRate: 129,
  total: 258,
  included: ['Wi-Fi'],
};

function availabilityWith(rooms: unknown[]) {
  return http.get('*/api/v1/public/availability', () =>
    HttpResponse.json({ checkIn: '2030-01-10', checkOut: '2030-01-12', nights: 2, rooms }),
  );
}

function setDates(checkIn: string, checkOut: string) {
  fireEvent.change(screen.getByLabelText('Check-in'), { target: { value: checkIn } });
  fireEvent.change(screen.getByLabelText('Check-out'), { target: { value: checkOut } });
}

beforeEach(() => {
  store.dispatch(publicApi.util.resetApiState());
});

afterEach(() => {
  vi.restoreAllMocks();
});

test('greets the guest by their local hour', () => {
  vi.spyOn(Date.prototype, 'getHours').mockReturnValue(9);
  renderFrontOffice();
  expect(screen.getByRole('heading', { name: 'Good morning. Welcome.' })).toBeInTheDocument();
});

test('searching shows each free room with its nightly rate, total and inclusions', async () => {
  server.use(availabilityWith([ROOM_101]));
  renderFrontOffice();

  setDates('2030-01-10', '2030-01-12');
  await userEvent.click(screen.getByRole('button', { name: 'Search rooms' }));

  const offer = await screen.findByTestId('offer-101');
  expect(offer).toHaveTextContent('$129.00 per night');
  expect(offer).toHaveTextContent('Total for 2 nights: $258.00');
  expect(offer).toHaveTextContent('Wi-Fi');
});

test('asks for both dates before searching', async () => {
  renderFrontOffice();
  await userEvent.click(screen.getByRole('button', { name: 'Search rooms' }));
  expect(screen.getByRole('alert')).toHaveTextContent('Choose a check-in and a check-out date.');
});

test('rejects a check-out that is not after check-in before calling the server', async () => {
  renderFrontOffice();
  setDates('2030-01-12', '2030-01-10');
  await userEvent.click(screen.getByRole('button', { name: 'Search rooms' }));
  expect(screen.getByRole('alert')).toHaveTextContent('Check-out must be after check-in.');
});

test('shows the server rule message when the stay is too long', async () => {
  server.use(
    http.get('*/api/v1/public/availability', () =>
      HttpResponse.json({ error: 'a stay can be at most 14 nights (requested 20)' }, { status: 400 })),
  );
  renderFrontOffice();
  setDates('2030-01-10', '2030-01-30');
  await userEvent.click(screen.getByRole('button', { name: 'Search rooms' }));

  expect(await screen.findByRole('alert')).toHaveTextContent('A stay can be at most 14 nights (requested 20)');
});

test('says so when no room is free for the dates', async () => {
  server.use(availabilityWith([]));
  renderFrontOffice();
  setDates('2030-01-10', '2030-01-12');
  await userEvent.click(screen.getByRole('button', { name: 'Search rooms' }));

  expect(await screen.findByText(/no rooms are free for those dates/i)).toBeInTheDocument();
});

test('a guest can book a room and lands on the confirmation with the reference', async () => {
  let posted: unknown = null;
  server.use(
    availabilityWith([ROOM_101]),
    http.post('*/api/v1/public/bookings', async ({ request }) => {
      posted = await request.json();
      return HttpResponse.json(
        {
          bookingId: 'b-123',
          status: 'CONFIRMED',
          roomNumber: '101',
          roomType: 'STANDARD',
          checkIn: '2030-01-10',
          checkOut: '2030-01-12',
          nights: 2,
          total: 258,
          payAtCheckout: true,
        },
        { status: 201 },
      );
    }),
  );
  renderFrontOffice();
  setDates('2030-01-10', '2030-01-12');
  await userEvent.click(screen.getByRole('button', { name: 'Search rooms' }));
  await userEvent.click(await screen.findByRole('button', { name: 'Book room 101' }));

  await userEvent.type(screen.getByLabelText('Full name'), 'Jane Doe');
  await userEvent.type(screen.getByLabelText('Phone'), '+1 555 0100');
  await userEvent.click(screen.getByRole('button', { name: 'Confirm booking' }));

  expect(await screen.findByRole('heading', { name: 'Booking confirmed' })).toBeInTheDocument();
  expect(screen.getByTestId('booking-reference')).toHaveTextContent('b-123');
  expect(screen.getByText(/pay at checkout/i)).toBeInTheDocument();
  expect(posted).toEqual({
    roomId: 'room-101',
    checkIn: '2030-01-10',
    checkOut: '2030-01-12',
    guestName: 'Jane Doe',
    guestPhone: '+1 555 0100',
    guestEmail: null,
    adults: 1,
    children: 0,
  });
});

test('a room taken in the meantime shows a clear message and the guest stays on the form', async () => {
  server.use(
    availabilityWith([ROOM_101]),
    http.post('*/api/v1/public/bookings', () => HttpResponse.json({}, { status: 409 })),
  );
  renderFrontOffice();
  setDates('2030-01-10', '2030-01-12');
  await userEvent.click(screen.getByRole('button', { name: 'Search rooms' }));
  await userEvent.click(await screen.findByRole('button', { name: 'Book room 101' }));
  await userEvent.type(screen.getByLabelText('Full name'), 'Jane Doe');
  await userEvent.type(screen.getByLabelText('Phone'), '+1 555 0100');
  await userEvent.click(screen.getByRole('button', { name: 'Confirm booking' }));

  await waitFor(() =>
    expect(screen.getByRole('alert')).toHaveTextContent('That room was just booked for those dates'));
  expect(screen.getByRole('button', { name: 'Confirm booking' })).toBeInTheDocument();
});
