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

export interface BookingFormState extends CardFormState {
  guestName: string;
  checkIn: string;
  checkOut: string;
}

export type BookingFormErrors = Partial<Record<keyof BookingFormState, string>>;

export function validateBookingForm(values: BookingFormState): BookingFormErrors {
  const errors: BookingFormErrors = {};

  if (!values.guestName.trim()) {
    errors.guestName = 'Guest name is required';
  }

  if (!values.checkIn) {
    errors.checkIn = 'Check-in date is required';
  }

  if (!values.checkOut) {
    errors.checkOut = 'Check-out date is required';
  } else if (values.checkIn && values.checkOut <= values.checkIn) {
    errors.checkOut = 'Check-out must be after check-in';
  }

  return { ...errors, ...validateCardFields(values) };
}
