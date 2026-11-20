import { alertWarn } from './ui';

interface RetryBannerProps {
  onRetry: () => void;
}

/** Shown when the server can't be reached. Says what happened and what to do. */
export function RetryBanner({ onRetry }: RetryBannerProps) {
  return (
    <div role="alert" className={`mb-4 flex flex-wrap items-center justify-between gap-3 ${alertWarn}`}>
      <span>
        The hotel system can&apos;t be reached right now. Check the network connection. The screen will also try
        again on its own.
      </span>
      <button
        type="button"
        onClick={onRetry}
        className="min-h-12 shrink-0 rounded-lg border border-amber-500 bg-white px-4 font-semibold text-amber-900 hover:bg-amber-100"
      >
        Try again now
      </button>
    </div>
  );
}
