/** Display helpers only: no pricing or date rules live here (the server owns those). */

const money = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });

export function formatMoney(amount: number): string {
  return money.format(amount);
}

export function nightsLabel(nights: number): string {
  return nights === 1 ? '1 night' : `${nights} nights`;
}

/** Today's date in the guest's own local timezone, as YYYY-MM-DD for <input type="date">. */
export function localIsoDate(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}
