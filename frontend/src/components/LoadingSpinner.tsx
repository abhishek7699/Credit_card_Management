export function LoadingSpinner() {
  return (
    <div className="flex min-h-screen items-center justify-center">
      <div
        role="status"
        aria-label="Loading"
        className="h-10 w-10 animate-spin rounded-full border-4 border-blue-600 border-t-transparent"
      />
    </div>
  );
}
