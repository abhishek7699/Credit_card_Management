import { expect, test } from '@playwright/test';

/**
 * One smoke test against a live docker-compose stack + `npm run dev`
 * (see README.md's "Frontend" section for the exact commands):
 * login -> book a vacant room -> booking reaches CONFIRMED -> check it in ->
 * board shows that room OCCUPIED.
 *
 * Picks its room by the board's actual VACANT_CLEAN status, not by room
 * number — scripts/e2e-test.sh's own step 14 hit a real bug doing the
 * opposite (picking by availability search instead of current status) when
 * a room was left over from an earlier manual demo.
 */
test('login, book a room, confirm, check in, board shows OCCUPIED', async ({ page }) => {
  await page.goto('/staff');

  await expect(page.getByText('Staff Sign In')).toBeVisible();
  await page.getByLabel('Front Desk').check();
  await page.getByRole('button', { name: 'Sign In' }).click();

  await expect(page.getByText('Room Board')).toBeVisible({ timeout: 15_000 });

  // A VACANT_CLEAN room can still have an arriving-today booking pending
  // check-in (that's the normal state right up until check-in happens) — a
  // room left in that state by an earlier run offers Check In, not the
  // booking form. Walk the vacant rooms until one actually opens the form.
  const vacantRooms = page.locator('[data-status="VACANT_CLEAN"]');
  const vacantCount = await vacantRooms.count();
  let roomNumber: string | null = null;
  for (let i = 0; i < vacantCount; i++) {
    await vacantRooms.nth(i).click();
    const isBookable = await page.getByLabel('Guest Name').isVisible({ timeout: 2_000 }).catch(() => false);
    if (isBookable) {
      roomNumber = await vacantRooms.nth(i).getAttribute('data-testid');
      break;
    }
    await page.getByRole('button', { name: 'Close panel' }).click();
  }
  if (!roomNumber) {
    throw new Error('No plain-bookable VACANT_CLEAN room found on the board');
  }

  const today = new Date();
  const tomorrow = new Date(today);
  tomorrow.setDate(tomorrow.getDate() + 1);
  const isoDate = (d: Date) => d.toISOString().slice(0, 10);

  await page.getByLabel('Guest Name').fill('Playwright Smoke Guest');
  await page.getByLabel('Check-in').fill(isoDate(today));
  await page.getByLabel('Check-out').fill(isoDate(tomorrow));
  await page.getByLabel('Card Number').fill('4111111111111111');
  await page.getByLabel('Expiry').fill('12/30');
  await page.getByLabel('CVV').fill('123');
  await page.getByLabel('Cardholder Name').fill('Playwright Smoke Guest');
  await page.getByRole('button', { name: 'Submit Booking' }).click();

  await expect(page.getByText('Confirmed')).toBeVisible({ timeout: 30_000 });

  await page.getByRole('button', { name: 'Close panel' }).click();

  // The board's own cached data at the moment of booking confirmation
  // doesn't carry the new arrivingBookingId yet — that only shows up once
  // the board's own 10s poll refetches. Wait past one cycle before
  // re-clicking, same room, now carrying today's arriving booking: the
  // board surfaces this itself (the whole point of the approved additive
  // backend change), not from anything this test session remembered.
  await page.waitForTimeout(11_000);
  await page.locator(`[data-testid="${roomNumber}"]`).click();
  await page.getByRole('button', { name: 'Check In' }).click();
  await expect(page.getByText('Checked in.')).toBeVisible({ timeout: 15_000 });

  await page.getByRole('button', { name: 'Close panel' }).click();

  // Same margin reasoning as the wait above this one — one 10s poll cycle,
  // plus headroom for however much of it already elapsed during the
  // check-in click/render above.
  await expect(page.locator(`[data-testid="${roomNumber}"]`)).toHaveAttribute(
    'data-status',
    'OCCUPIED',
    { timeout: 25_000 },
  );
});
