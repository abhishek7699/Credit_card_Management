import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { Provider } from 'react-redux';
import { expect, test } from 'vitest';
import { server } from '../../mocks/server';
import { store } from '../../store';
import { ChargesPanel } from './ChargesPanel';
import type { Booking } from '../../types/staff';

function booking(overrides: Partial<Booking> = {}): Booking {
  return {
    bookingId: 'b1',
    roomId: 'r1',
    guestName: 'Jane Doe',
    checkIn: '2026-12-01',
    checkOut: '2026-12-03',
    status: 'CHECKED_IN',
    cancellationReason: null,
    transactionId: 'txn-1',
    charges: [],
    balanceDue: 0,
    ...overrides,
  };
}

function renderPanel(b: Booking) {
  return render(
    <Provider store={store}>
      <ChargesPanel booking={b} />
    </Provider>,
  );
}

test('renders existing charges and the balance', () => {
  renderPanel(
    booking({
      charges: [
        { chargeId: 'c1', type: 'ROOM_SERVICE', description: 'Club sandwich', amount: 18.5, createdAt: '2026-10-30T18:04:11Z' },
      ],
      balanceDue: 18.5,
    }),
  );

  expect(screen.getByText('Club sandwich')).toBeInTheDocument();
  expect(screen.getByText('$18.50')).toBeInTheDocument();
  expect(screen.getByText(/balance due: \$18\.50/i)).toBeInTheDocument();
});

test('shows "No charges yet" when the booking has none', () => {
  renderPanel(booking());
  expect(screen.getByText(/no charges yet/i)).toBeInTheDocument();
});

test('submitting the add-charge form calls the mutation with the right payload', async () => {
  let receivedBody: unknown;
  server.use(
    http.post('*/api/v1/bookings/b1/charges', async ({ request }) => {
      receivedBody = await request.json();
      return HttpResponse.json(
        { chargeId: 'c2', type: 'MINIBAR', description: 'Soda', amount: 6, createdAt: '2026-10-30T19:00:00Z' },
        { status: 201 },
      );
    }),
  );
  renderPanel(booking());

  await userEvent.selectOptions(screen.getByLabelText(/charge type/i), 'MINIBAR');
  await userEvent.type(screen.getByLabelText(/description/i), 'Soda');
  await userEvent.type(screen.getByLabelText(/amount/i), '6');
  await userEvent.click(screen.getByRole('button', { name: /add charge/i }));

  await screen.findByText(/add charge/i); // settle
  expect(receivedBody).toEqual({ type: 'MINIBAR', description: 'Soda', amount: 6 });
});

test('a non-positive amount is rejected client-side without calling the mutation', async () => {
  let called = false;
  server.use(
    http.post('*/api/v1/bookings/b1/charges', () => {
      called = true;
      return HttpResponse.json({}, { status: 201 });
    }),
  );
  renderPanel(booking());

  await userEvent.type(screen.getByLabelText(/description/i), 'Soda');
  await userEvent.type(screen.getByLabelText(/amount/i), '0');
  await userEvent.click(screen.getByRole('button', { name: /add charge/i }));

  expect(screen.getByText(/enter an amount greater than 0/i)).toBeInTheDocument();
  expect(called).toBe(false);
});

test('a missing description is rejected client-side without calling the mutation', async () => {
  let called = false;
  server.use(
    http.post('*/api/v1/bookings/b1/charges', () => {
      called = true;
      return HttpResponse.json({}, { status: 201 });
    }),
  );
  renderPanel(booking());

  await userEvent.type(screen.getByLabelText(/amount/i), '6');
  await userEvent.click(screen.getByRole('button', { name: /add charge/i }));

  expect(screen.getByText(/description is required/i)).toBeInTheDocument();
  expect(called).toBe(false);
});

test('a failed add-charge call shows an inline error', async () => {
  server.use(http.post('*/api/v1/bookings/b1/charges', () => HttpResponse.json({ error: 'conflict' }, { status: 409 })));
  renderPanel(booking());

  await userEvent.type(screen.getByLabelText(/description/i), 'Soda');
  await userEvent.type(screen.getByLabelText(/amount/i), '6');
  await userEvent.click(screen.getByRole('button', { name: /add charge/i }));

  expect(await screen.findByRole('alert')).toBeInTheDocument();
});
