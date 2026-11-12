export interface CardFormState {
  cardNumber: string;
  expiry: string;
  cvv: string;
  cardholderName: string;
}

export type CardFormErrors = Partial<Record<keyof CardFormState, string>>;

/** Shared by BookingForm (new booking) and CheckOutCardForm (checkout with a
 *  balance due, layer 2c) — same card fields, same rules, one place to change. */
export function validateCardFields(values: CardFormState): CardFormErrors {
  const errors: CardFormErrors = {};

  const digits = values.cardNumber.replace(/\s+/g, '');
  if (digits.length === 0) {
    errors.cardNumber = 'Card number is required';
  } else if (!/^\d{13,19}$/.test(digits)) {
    errors.cardNumber = 'Enter a valid card number (13–19 digits)';
  }

  if (!values.expiry) {
    errors.expiry = 'Expiry date is required';
  } else if (!/^\d{2}\/\d{2}$/.test(values.expiry)) {
    errors.expiry = 'Use MM/YY format';
  }

  if (!values.cvv) {
    errors.cvv = 'CVV is required';
  } else if (!/^\d{3,4}$/.test(values.cvv)) {
    errors.cvv = 'CVV must be 3 or 4 digits';
  }

  if (!values.cardholderName.trim()) {
    errors.cardholderName = 'Cardholder name is required';
  }

  return errors;
}

/** No card, of any kind (FR-017) -- booking creation no longer extends
 *  CardFormState. guestName/guestPhone/checkIn/checkOut are required (FR-019);
 *  every other field is optional (FR-018). adults/children are kept as plain
 *  strings here, like every other input value, and parsed/defaulted only at
 *  submit time (BookingForm.tsx) -- not here, since "blank" and "0" must be
 *  told apart for adults/children's own defaulting rule. */
export interface BookingFormState {
  guestName: string;
  guestPhone: string;
  checkIn: string;
  checkOut: string;
  guestEmail: string;
  visitingFrom: string;
  adults: string;
  children: string;
  expectedArrivalTime: string;
  expectedDepartureTime: string;
  notes: string;
}

export type BookingFormErrors = Partial<Record<keyof BookingFormState, string>>;

export function validateBookingForm(values: BookingFormState): BookingFormErrors {
  const errors: BookingFormErrors = {};

  if (!values.guestName.trim()) {
    errors.guestName = 'Guest name is required';
  }

  if (!values.guestPhone.trim()) {
    errors.guestPhone = 'Phone number is required';
  }

  if (!values.checkIn) {
    errors.checkIn = 'Check-in date is required';
  }

  if (!values.checkOut) {
    errors.checkOut = 'Check-out date is required';
  } else if (values.checkIn && values.checkOut <= values.checkIn) {
    errors.checkOut = 'Check-out must be after check-in';
  }

  if (values.adults.trim() && (!/^\d+$/.test(values.adults.trim()) || Number(values.adults) < 1)) {
    errors.adults = 'Adults must be a whole number of at least 1';
  }

  if (values.children.trim() && (!/^\d+$/.test(values.children.trim()) || Number(values.children) < 0)) {
    errors.children = 'Children must be a whole number of at least 0';
  }

  return errors;
}
