import { expect, test } from 'vitest';
import { greetingFor } from './greeting';
import { errorMessage } from './apiError';
import { formatMoney, localIsoDate, nightsLabel } from './format';
import { validateGuestForm } from './validation';

test.each([
  [0, 'Good evening'],
  [4, 'Good evening'],
  [5, 'Good morning'],
  [11, 'Good morning'],
  [12, 'Good afternoon'],
  [17, 'Good afternoon'],
  [18, 'Good evening'],
  [23, 'Good evening'],
])('hour %i greets with "%s"', (hour, expected) => {
  expect(greetingFor(hour)).toBe(expected);
});

test('formats money in US dollars with cents', () => {
  expect(formatMoney(258)).toBe('$258.00');
  expect(formatMoney(129.5)).toBe('$129.50');
});

test('pluralises the night count', () => {
  expect(nightsLabel(1)).toBe('1 night');
  expect(nightsLabel(3)).toBe('3 nights');
});

test('builds the local calendar date with zero-padded month and day', () => {
  expect(localIsoDate(new Date(2030, 0, 5, 23, 30))).toBe('2030-01-05');
  expect(localIsoDate(new Date(2030, 11, 25))).toBe('2030-12-25');
});

test('shows the server rule message, with its first letter capitalised', () => {
  expect(errorMessage({ status: 400, data: { error: 'a stay can be at most 14 nights' } }))
    .toBe('A stay can be at most 14 nights');
});

test('maps a 409 with no message to a clear "room taken" message', () => {
  expect(errorMessage({ status: 409, data: {} })).toMatch(/just booked/i);
});

test('falls back to a generic message for anything else', () => {
  expect(errorMessage({ status: 500, data: 'boom' })).toBe('Something went wrong. Please try again.');
  expect(errorMessage({ status: 400, data: { error: '' } })).toBe('Something went wrong. Please try again.');
  expect(errorMessage(null)).toBe('Something went wrong. Please try again.');
  expect(errorMessage('plain string')).toBe('Something went wrong. Please try again.');
  expect(errorMessage({ status: 400, data: null })).toBe('Something went wrong. Please try again.');
  expect(errorMessage({ status: 400, data: { error: 42 } })).toBe('Something went wrong. Please try again.');
});

test('validateGuestForm accepts a complete guest and an optional valid email', () => {
  expect(validateGuestForm({
    guestName: 'Jane', guestPhone: '555', guestEmail: 'jane@example.com', adults: '2', children: '0',
  })).toEqual({});
});

test('validateGuestForm flags a non-numeric child count', () => {
  expect(validateGuestForm({
    guestName: 'Jane', guestPhone: '555', guestEmail: '', adults: '1', children: 'two',
  })).toEqual({ children: 'Enter 0 or more' });
});
