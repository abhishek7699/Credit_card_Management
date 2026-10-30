interface RetryBannerProps {
  onRetry: () => void;
}

export function RetryBanner({ onRetry }: RetryBannerProps) {
  return (
    <div role="alert" className="m-4 flex items-center justify-between rounded border border-amber-300 bg-amber-50 p-4 text-sm text-amber-800">
      <span>Can&apos;t reach the server right now. We&apos;ll keep retrying automatically.</span>
      <button
        type="button"
        onClick={onRetry}
        className="ml-4 shrink-0 rounded border border-amber-400 bg-white px-3 py-1 font-medium text-amber-800 hover:bg-amber-100"
      >
        Retry now
      </button>
    </div>
  );
}
