import { expect, test, type Page, type Route } from '@playwright/test';

/**
 * Screenshots of every staff screen, for the README and for before/after review.
 *
 * Runs against `npm run dev` with the API mocked at the network layer, so it needs no
 * docker-compose stack and produces the same pictures every run. Files are written to
 * docs/screenshots/<SCREENSHOT_PHASE>/ at the repo root (default phase: "after").
 */

const PHASE = process.env['SCREENSHOT_PHASE'] ?? 'after';
const OUT = new URL(`../../docs/screenshots/${PHASE}/`, import.meta.url);

// iPad landscape: the tablet orientation the front desk uses.
test.use({ viewport: { width: 1180, height: 820 } });

const room = (
  roomId: string,
  roomNumber: string,
  position: number,
  status: 'VACANT_CLEAN' | 'OCCUPIED' | 'DIRTY' | 'MAINTENANCE',
  extra: Partial<{
    guestName: string;
    currentBookingId: string;
    arrivingBookingId: string;
    arrivingGuestName: string;
    hasBalanceDue: boolean;
  }> = {},
) => ({
  roomId,
  roomNumber,
  position,
  status,
  guestName: extra.guestName ?? null,
  checkIn: null,
  checkOut: null,
  currentBookingId: extra.currentBookingId ?? null,
  arrivingBookingId: extra.arrivingBookingId ?? null,
  arrivingGuestName: extra.arrivingGuestName ?? null,
  hasBalanceDue: extra.hasBalanceDue ?? null,
});

const BOARD = [
  {
    floor: 1,
    rooms: [
      room('r101', '101', 1, 'VACANT_CLEAN'),
      room('r102', '102', 2, 'OCCUPIED', { guestName: 'Jane Doe', currentBookingId: 'b102', hasBalanceDue: true }),
      room('r103', '103', 3, 'DIRTY'),
      room('r104', '104', 4, 'MAINTENANCE'),
      room('r105', '105', 5, 'VACANT_CLEAN', { arrivingBookingId: 'b105', arrivingGuestName: 'Sam Lee' }),
      room('r106', '106', 6, 'VACANT_CLEAN'),
    ],
  },
  {
    floor: 2,
    rooms: [
      room('r201', '201', 1, 'OCCUPIED', { guestName: 'Priya Shah', currentBookingId: 'b201', hasBalanceDue: false }),
      room('r202', '202', 2, 'VACANT_CLEAN'),
      room('r203', '203', 3, 'DIRTY'),
      room('r204', '204', 4, 'VACANT_CLEAN'),
      room('r205', '205', 5, 'VACANT_CLEAN'),
      room('r206', '206', 6, 'MAINTENANCE'),
    ],
  },
];

function booking(bookingId: string, roomId: string, status: string, balanceDue: number, guestName: string) {
  return {
    bookingId,
    roomId,
    guestName,
    guestPhone: '+1-555-0100',
    guestEmail: 'guest@example.com',
    visitingFrom: 'Austin, TX',
    adults: 2,
    children: 0,
    expectedArrivalTime: '15:00',
    expectedDepartureTime: '11:00',
    notes: null,
    checkIn: '2026-11-19',
    checkOut: '2026-11-21',
    status,
    cancellationReason: null,
    charges: balanceDue > 0
      ? [{ chargeId: 'c1', type: 'MINIBAR', description: 'Minibar', amount: 42.5, createdAt: '2026-11-19T20:00:00Z' }]
      : [],
    balanceDue,
  };
}

async function mockApi(page: Page, options: { board?: unknown; boardStatus?: number; boardDelayMs?: number } = {}) {
  await page.route('**/dev/login', (route: Route) =>
    route.fulfill({ json: { token: 'test-token' } }),
  );
  await page.route('**/api/v1/rooms/board', async (route: Route) => {
    if (options.boardDelayMs) await new Promise((r) => setTimeout(r, options.boardDelayMs));
    if (options.boardStatus) return route.fulfill({ status: options.boardStatus, json: { message: 'down' } });
    return route.fulfill({ json: options.board ?? BOARD });
  });
  await page.route('**/api/v1/bookings/b102', (route: Route) =>
    route.fulfill({ json: booking('b102', 'r102', 'CHECKED_IN', 42.5, 'Jane Doe') }),
  );
  await page.route('**/api/v1/bookings/b201', (route: Route) =>
    route.fulfill({ json: booking('b201', 'r201', 'CHECKED_IN', 0, 'Priya Shah') }),
  );
  await page.route('**/api/v1/bookings/b105', (route: Route) =>
    route.fulfill({ json: booking('b105', 'r105', 'CONFIRMED', 0, 'Sam Lee') }),
  );
}

/** Puts the page straight into a logged-in front-desk session, as login would have. */
async function signedInAsFrontDesk(page: Page) {
  await page.addInitScript(() => {
    sessionStorage.setItem('staffSession', JSON.stringify({ role: 'FRONT_DESK', token: 'test-token' }));
  });
}

async function shot(page: Page, name: string) {
  await page.waitForTimeout(300); // let transitions settle
  await page.screenshot({ path: new URL(name, OUT).pathname, fullPage: false });
}

test.beforeAll(async () => {
  const fs = await import('node:fs');
  fs.mkdirSync(new URL(OUT).pathname, { recursive: true });
});

test('login screen', async ({ page }) => {
  await mockApi(page);
  await page.goto('/staff');
  await expect(page.getByText('Staff Sign In')).toBeVisible();
  await shot(page, '01-login.png');
});

test('room board', async ({ page }) => {
  await mockApi(page);
  await signedInAsFrontDesk(page);
  await page.goto('/staff');
  await expect(page.getByTestId('room-101')).toBeVisible();
  await shot(page, '02-room-board.png');
});

test('room panel: vacant room, booking form', async ({ page }) => {
  await mockApi(page);
  await signedInAsFrontDesk(page);
  await page.goto('/staff');
  await page.getByTestId('room-106').click();
  await expect(page.getByLabel('Guest Name')).toBeVisible();
  await shot(page, '03-panel-book-vacant-room.png');
});

test('room panel: arriving guest, check in', async ({ page }) => {
  await mockApi(page);
  await signedInAsFrontDesk(page);
  await page.goto('/staff');
  await page.getByTestId('room-105').click();
  await expect(page.getByRole('button', { name: 'Check In' })).toBeVisible();
  await shot(page, '04-panel-check-in.png');
});

test('room panel: occupied with balance, check out and charges', async ({ page }) => {
  await mockApi(page);
  await signedInAsFrontDesk(page);
  await page.goto('/staff');
  await page.getByTestId('room-102').click();
  await expect(page.getByRole('heading', { name: 'Charges' })).toBeVisible();
  await shot(page, '05-panel-checkout-and-charges.png');
});

test('room panel: dirty room, status change', async ({ page }) => {
  await mockApi(page);
  await signedInAsFrontDesk(page);
  await page.goto('/staff');
  await page.getByTestId('room-103').click();
  await expect(page.getByRole('button', { name: 'Mark Clean' })).toBeVisible();
  await shot(page, '06-panel-dirty-room.png');
});

test('room board: loading', async ({ page }) => {
  await mockApi(page, { boardDelayMs: 4000 });
  await signedInAsFrontDesk(page);
  await page.goto('/staff');
  await page.waitForTimeout(500);
  await shot(page, '07-room-board-loading.png');
});

test('room board: server error', async ({ page }) => {
  await mockApi(page, { boardStatus: 500 });
  await signedInAsFrontDesk(page);
  await page.goto('/staff');
  await expect(page.getByRole('alert')).toBeVisible({ timeout: 15_000 });
  await shot(page, '08-room-board-error.png');
});

test('room board: no rooms yet', async ({ page }) => {
  await mockApi(page, { board: [] });
  await signedInAsFrontDesk(page);
  await page.goto('/staff');
  await page.waitForTimeout(800);
  await shot(page, '09-room-board-empty.png');
});
