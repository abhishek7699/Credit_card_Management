import { useEffect } from 'react';

interface ToastProps {
  message: string;
  onDismiss: () => void;
}

export function Toast({ message, onDismiss }: ToastProps) {
  useEffect(() => {
    const id = setTimeout(onDismiss, 2500);
    return () => clearTimeout(id);
  }, [onDismiss]);

  return (
    <div
      role="status"
      aria-live="polite"
      className="fixed bottom-4 right-4 z-50 rounded-lg bg-gray-900 px-4 py-3 text-sm text-white shadow-xl"
    >
      {message}
    </div>
  );
}
