import { expect, test } from 'vitest';
import { validateBookingForm, type BookingFormState } from './validation';

const VALID: BookingFormState = {
  guestName: 'Jane Doe',
  checkIn: '2026-12-01',
  checkOut: '2026-12-03',
  cardNumber: '4111111111111111',
  expiry: '12/30',
  cvv: '123',
  cardholderName: 'Jane Doe',
};

test('a fully valid form has no errors', () => {
  expect(validateBookingForm(VALID)).toEqual({});
});

test('whitespace-only guest name is treated as missing', () => {
  expect(validateBookingForm({ ...VALID, guestName: '   ' }).guestName).toBeDefined();
});

test('checkOut equal to checkIn is rejected (must be strictly after)', () => {
  expect(validateBookingForm({ ...VALID, checkOut: VALID.checkIn }).checkOut).toBeDefined();
});

test('a too-short card number is rejected as an invalid format, not "required"', () => {
  const errors = validateBookingForm({ ...VALID, cardNumber: '1234' });
  expect(errors.cardNumber).toMatch(/valid card number/i);
});

test('a malformed expiry is rejected as an invalid format, not "required"', () => {
  const errors = validateBookingForm({ ...VALID, expiry: '2030' });
  expect(errors.expiry).toMatch(/MM\/YY/i);
});

test('a non-numeric or wrong-length cvv is rejected as an invalid format, not "required"', () => {
  const errors = validateBookingForm({ ...VALID, cvv: 'abcde' });
  expect(errors.cvv).toMatch(/3 or 4 digits/i);
});

test('whitespace-only cardholder name is treated as missing', () => {
  expect(validateBookingForm({ ...VALID, cardholderName: '   ' }).cardholderName).toBeDefined();
});
