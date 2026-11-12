import { expect, test } from 'vitest';
import { validateBookingForm, validateCardFields, type BookingFormState, type CardFormState } from './validation';

const VALID: BookingFormState = {
  guestName: 'Jane Doe',
  guestPhone: '+1-555-0100',
  checkIn: '2026-12-01',
  checkOut: '2026-12-03',
  guestEmail: '',
  visitingFrom: '',
  adults: '',
  children: '',
  expectedArrivalTime: '',
  expectedDepartureTime: '',
  notes: '',
};

test('a fully valid form (minimal required fields only) has no errors', () => {
  expect(validateBookingForm(VALID)).toEqual({});
});

test('a fully valid form with every optional field filled in has no errors', () => {
  expect(
    validateBookingForm({
      ...VALID,
      guestEmail: 'jane@example.com',
      visitingFrom: 'Chicago, IL',
      adults: '2',
      children: '1',
      expectedArrivalTime: '15:00',
      expectedDepartureTime: '11:00',
      notes: 'Requested a crib',
    }),
  ).toEqual({});
});

test('whitespace-only guest name is treated as missing', () => {
  expect(validateBookingForm({ ...VALID, guestName: '   ' }).guestName).toBeDefined();
});

test('whitespace-only guest phone is treated as missing', () => {
  expect(validateBookingForm({ ...VALID, guestPhone: '   ' }).guestPhone).toBeDefined();
});

test('checkOut equal to checkIn is rejected (must be strictly after)', () => {
  expect(validateBookingForm({ ...VALID, checkOut: VALID.checkIn }).checkOut).toBeDefined();
});

test('missing checkIn is reported as required', () => {
  expect(validateBookingForm({ ...VALID, checkIn: '' }).checkIn).toBeDefined();
});

test('adults left blank is not an error (defaults to 1 server-side, FR-018)', () => {
  expect(validateBookingForm({ ...VALID, adults: '' }).adults).toBeUndefined();
});

test('adults of 0 is rejected', () => {
  expect(validateBookingForm({ ...VALID, adults: '0' }).adults).toBeDefined();
});

test('a non-numeric adults value is rejected', () => {
  expect(validateBookingForm({ ...VALID, adults: 'abc' }).adults).toBeDefined();
});

test('children left blank is not an error (defaults to 0 server-side, FR-018)', () => {
  expect(validateBookingForm({ ...VALID, children: '' }).children).toBeUndefined();
});

test('children of 0 is valid (zero children is the default, not an error)', () => {
  expect(validateBookingForm({ ...VALID, children: '0' }).children).toBeUndefined();
});

test('a negative children value is rejected', () => {
  expect(validateBookingForm({ ...VALID, children: '-1' }).children).toBeDefined();
});

// ── validateCardFields (shared with CheckOutCardForm, layer 2c -- card is only
// ever entered at checkout now, never at booking) ─────────────────────────────

const VALID_CARD: CardFormState = {
  cardNumber: '4111111111111111',
  expiry: '12/30',
  cvv: '123',
  cardholderName: 'Jane Doe',
};

test('validateCardFields: a fully valid card has no errors', () => {
  expect(validateCardFields(VALID_CARD)).toEqual({});
});

test('validateCardFields: missing card number is required, not an invalid format', () => {
  expect(validateCardFields({ ...VALID_CARD, cardNumber: '' }).cardNumber).toMatch(/required/i);
});

test('validateCardFields: a too-short card number is rejected as an invalid format', () => {
  expect(validateCardFields({ ...VALID_CARD, cardNumber: '1234' }).cardNumber).toMatch(/valid card number/i);
});

test('validateCardFields: a malformed expiry is rejected as an invalid format', () => {
  expect(validateCardFields({ ...VALID_CARD, expiry: '2030' }).expiry).toMatch(/MM\/YY/i);
});

test('validateCardFields: a non-numeric or wrong-length cvv is rejected as an invalid format', () => {
  expect(validateCardFields({ ...VALID_CARD, cvv: 'abcde' }).cvv).toMatch(/3 or 4 digits/i);
});

test('validateCardFields: whitespace-only cardholder name is treated as missing', () => {
  expect(validateCardFields({ ...VALID_CARD, cardholderName: '   ' }).cardholderName).toBeDefined();
});
