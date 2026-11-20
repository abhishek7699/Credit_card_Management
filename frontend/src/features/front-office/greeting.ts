/** A greeting for the guest's local hour (0-23): morning 05:00-11:59, afternoon 12:00-17:59,
 *  evening otherwise. */
export function greetingFor(hour: number): string {
  if (hour >= 5 && hour < 12) return 'Good morning';
  if (hour >= 12 && hour < 18) return 'Good afternoon';
  return 'Good evening';
}
