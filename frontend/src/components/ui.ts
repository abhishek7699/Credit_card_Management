/**
 * One set of staff-screen styles, so every screen uses the same spacing, type sizes,
 * colors and button shapes. Import these instead of writing Tailwind classes inline.
 *
 * Touch: every interactive control is at least 48px tall (`min-h-12`), so it can be hit
 * reliably with a finger on a tablet held in landscape.
 */

/** Primary action: book, check in, check out, add charge, sign in. */
export const btnPrimary =
  'flex min-h-12 w-full items-center justify-center rounded-lg bg-indigo-600 px-4 py-3 text-base font-semibold text-white shadow-sm hover:bg-indigo-700 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-500 disabled:cursor-not-allowed disabled:opacity-50';

/** Secondary action: mark clean, close, retry. */
export const btnSecondary =
  'flex min-h-12 w-full items-center justify-center rounded-lg border border-gray-300 bg-white px-4 py-3 text-base font-medium text-gray-800 hover:bg-gray-50 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-indigo-500 disabled:cursor-not-allowed disabled:opacity-50';

/** Text control in the sidebar and panel headers. */
export const btnGhost =
  'flex min-h-12 items-center rounded-lg px-3 text-base text-gray-700 hover:bg-gray-100 hover:text-gray-900 focus-visible:outline focus-visible:outline-2 focus-visible:outline-indigo-500';

export const fieldLabel = 'block text-sm font-medium text-gray-700';

export const input =
  'mt-1 w-full min-h-12 rounded-lg border border-gray-300 bg-white px-3 py-2 text-base text-gray-900 focus-visible:outline focus-visible:outline-2 focus-visible:outline-indigo-500';

export const hint = 'text-gray-500';

export const fieldError = 'mt-1 text-sm text-red-700';

export const alertError = 'rounded-lg border border-red-300 bg-red-50 p-3 text-base text-red-800';

export const alertWarn = 'rounded-lg border border-amber-300 bg-amber-50 p-3 text-base text-amber-900';

export const alertSuccess = 'rounded-lg border border-green-300 bg-green-50 p-3 text-base text-green-900';

/** Section heading inside a panel or page. */
export const sectionTitle = 'text-base font-semibold text-gray-900';

/** Muted text for loading and empty states. */
export const stateText = 'text-base text-gray-600';
