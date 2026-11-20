/** The message to show a guest for a failed request. The server's own rule message
 *  (e.g. "a stay can be at most 14 nights") is shown as-is when it sends one. */
export function errorMessage(error: unknown): string {
  if (typeof error === 'object' && error !== null) {
    const { data, status } = error as { data?: unknown; status?: unknown };
    if (typeof data === 'object' && data !== null && 'error' in data) {
      const message = (data as { error: unknown }).error;
      if (typeof message === 'string' && message.length > 0) {
        return capitalise(message);
      }
    }
    if (status === 409) {
      return 'That room was just booked for those dates. Please choose another room.';
    }
  }
  return 'Something went wrong. Please try again.';
}

function capitalise(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1);
}
