export interface FormValues {
  cardNumber: string;
  expiry: string;
  cvv: string;
  cardholderName: string;
  amount: string;
  currency: string;
}

export type FormErrors = Partial<Record<keyof FormValues, string>>;

export function validatePaymentForm(values: FormValues): FormErrors {
  const errors: FormErrors = {};

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
  } else {
    const [mm, yy] = values.expiry.split('/');
    const month = parseInt(mm ?? '0', 10);
    const year = 2000 + parseInt(yy ?? '0', 10);
    const now = new Date();
    const expDate = new Date(year, month - 1, 1);
    const firstOfThisMonth = new Date(now.getFullYear(), now.getMonth(), 1);
    if (month < 1 || month > 12) errors.expiry = 'Invalid month';
    else if (expDate < firstOfThisMonth) errors.expiry = 'Card has expired';
  }

  if (!values.cvv) {
    errors.cvv = 'CVV is required';
  } else if (!/^\d{3,4}$/.test(values.cvv)) {
    errors.cvv = 'CVV must be 3 or 4 digits';
  }

  if (!values.cardholderName.trim()) {
    errors.cardholderName = 'Cardholder name is required';
  }

  if (!values.amount) {
    errors.amount = 'Amount is required';
  } else {
    const n = parseFloat(values.amount);
    if (isNaN(n) || n <= 0) errors.amount = 'Amount must be greater than 0';
  }

  return errors;
}
