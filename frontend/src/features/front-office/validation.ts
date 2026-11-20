/** Guest details, checked only for presence and shape here. Date windows, stay length and
 *  the room's availability are checked by the server, and its message is shown instead. */

export interface GuestFormValues {
  guestName: string;
  guestPhone: string;
  guestEmail: string;
  adults: string;
  children: string;
}

export type GuestFormErrors = Partial<Record<keyof GuestFormValues, string>>;

export function validateGuestForm(values: GuestFormValues): GuestFormErrors {
  const errors: GuestFormErrors = {};
  if (!values.guestName.trim()) {
    errors.guestName = 'Enter your name';
  }
  if (!values.guestPhone.trim()) {
    errors.guestPhone = 'Enter a phone number';
  }
  const email = values.guestEmail.trim();
  if (email && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
    errors.guestEmail = 'Enter a valid email address';
  }
  if (!/^\d+$/.test(values.adults) || Number(values.adults) < 1) {
    errors.adults = 'At least one adult';
  }
  if (!/^\d+$/.test(values.children)) {
    errors.children = 'Enter 0 or more';
  }
  return errors;
}
