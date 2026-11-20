interface LoadingSpinnerProps {
  label?: string;
}

/** Loading state for a whole area: a spinner with a plain-language label under it. */
export function LoadingSpinner({ label = 'Loading…' }: LoadingSpinnerProps) {
  return (
    <div className="flex min-h-64 flex-col items-center justify-center gap-4 p-6">
      <div
        role="status"
        aria-label="Loading"
        className="h-12 w-12 animate-spin rounded-full border-4 border-indigo-600 border-t-transparent"
      />
      <p className="text-base text-gray-600">{label}</p>
    </div>
  );
}
