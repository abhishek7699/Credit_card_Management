import { useState } from 'react';
import { useAppDispatch } from '../../store';
import { login } from '../../store/authSlice';
import { hashPin } from './hashPin';

interface Props {
  device: { email: string; pinHash: string };
  onSignOut: () => void;
}

export function PinUnlock({ device, onSignOut }: Props) {
  const dispatch = useAppDispatch();
  const [pin, setPin] = useState('');
  const [error, setError] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSubmitting(true);
    setError(false);

    const hash = await hashPin(pin);
    if (hash === device.pinHash) {
      dispatch(login());
    } else {
      setError(true);
      setPin('');
    }
    setSubmitting(false);
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-gray-50 px-4">
      <div className="w-full max-w-sm rounded-xl border border-gray-200 bg-white p-8 shadow-sm">
        <div className="mb-6 text-center">
          <div className="mx-auto mb-3 flex h-10 w-10 items-center justify-center rounded-lg bg-indigo-600 text-sm font-bold text-white">
            CG
          </div>
          <h1 className="text-lg font-semibold text-gray-900">Welcome back</h1>
          <p className="mt-1 text-sm text-gray-500">{device.email}</p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4" noValidate>
          <div>
            <label htmlFor="unlock-pin" className="block text-sm font-medium text-gray-700">
              Enter your PIN
            </label>
            <input
              id="unlock-pin"
              type="password"
              inputMode="numeric"
              maxLength={4}
              autoFocus
              value={pin}
              onChange={(e) => {
                setError(false);
                setPin(e.target.value.replace(/\D/g, '').slice(0, 4));
              }}
              className={`mt-1 w-full rounded-lg border px-3 py-2 text-center text-xl tracking-widest focus:outline-none focus:ring-2 focus:ring-indigo-500 ${
                error ? 'border-red-400' : 'border-gray-300'
              }`}
              placeholder="••••"
              autoComplete="current-password"
            />
            {error && (
              <p role="alert" className="mt-1 text-sm text-red-600">
                Incorrect PIN. Try again.
              </p>
            )}
          </div>

          <button
            type="submit"
            disabled={submitting || pin.length !== 4}
            className="w-full rounded-lg bg-indigo-600 px-4 py-2.5 text-sm font-medium text-white hover:bg-indigo-700 disabled:opacity-50"
          >
            {submitting ? 'Checking…' : 'Unlock'}
          </button>
        </form>

        <div className="mt-6 text-center">
          <button
            onClick={onSignOut}
            className="text-xs text-gray-400 hover:text-gray-600"
          >
            Not you? Sign out
          </button>
        </div>
      </div>
    </div>
  );
}
