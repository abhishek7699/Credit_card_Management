import { useState } from 'react';
import { useAppDispatch } from '../../store';
import { login } from '../../store/authSlice';
import { hashPin } from './hashPin';
import { setStoredDevice } from './deviceStorage';

interface Props {
  onRegistered: () => void;
}

export function PinRegistration({ onRegistered }: Props) {
  const dispatch = useAppDispatch();
  const [email, setEmail] = useState('');
  const [pin, setPin] = useState('');
  const [confirmPin, setConfirmPin] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);

    if (!/^\d{4}$/.test(pin)) {
      setError('PIN must be 4 digits.');
      return;
    }
    if (pin !== confirmPin) {
      setError('PINs do not match.');
      return;
    }

    setSubmitting(true);
    const pinHash = await hashPin(pin);
    setStoredDevice(email, pinHash);
    dispatch(login());
    onRegistered();
    setSubmitting(false);
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-gray-50 px-4">
      <div className="w-full max-w-sm rounded-xl border border-gray-200 bg-white p-8 shadow-sm">
        <div className="mb-6 text-center">
          <div className="mx-auto mb-3 flex h-10 w-10 items-center justify-center rounded-lg bg-indigo-600 text-sm font-bold text-white">
            CG
          </div>
          <h1 className="text-lg font-semibold text-gray-900">Create your PIN</h1>
          <p className="mt-1 text-sm text-gray-500">Set up your device access PIN</p>
        </div>

        <form onSubmit={handleSubmit} className="space-y-4" noValidate>
          <div>
            <label htmlFor="email" className="block text-sm font-medium text-gray-700">
              Email
            </label>
            <input
              id="email"
              type="email"
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              className="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500"
              placeholder="you@example.com"
            />
          </div>

          <div>
            <label htmlFor="pin" className="block text-sm font-medium text-gray-700">
              New PIN
            </label>
            <input
              id="pin"
              type="password"
              inputMode="numeric"
              maxLength={4}
              value={pin}
              onChange={(e) => setPin(e.target.value.replace(/\D/g, '').slice(0, 4))}
              className="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2 text-center text-xl tracking-widest focus:outline-none focus:ring-2 focus:ring-indigo-500"
              placeholder="••••"
              autoComplete="new-password"
            />
          </div>

          <div>
            <label htmlFor="confirm-pin" className="block text-sm font-medium text-gray-700">
              Confirm PIN
            </label>
            <input
              id="confirm-pin"
              type="password"
              inputMode="numeric"
              maxLength={4}
              value={confirmPin}
              onChange={(e) => setConfirmPin(e.target.value.replace(/\D/g, '').slice(0, 4))}
              className="mt-1 w-full rounded-lg border border-gray-300 px-3 py-2 text-center text-xl tracking-widest focus:outline-none focus:ring-2 focus:ring-indigo-500"
              placeholder="••••"
              autoComplete="new-password"
            />
          </div>

          {error && (
            <p role="alert" className="text-sm text-red-600">
              {error}
            </p>
          )}

          <button
            type="submit"
            disabled={submitting || !email}
            className="w-full rounded-lg bg-indigo-600 px-4 py-2.5 text-sm font-medium text-white hover:bg-indigo-700 disabled:opacity-50"
          >
            {submitting ? 'Setting up…' : 'Create PIN & Enter'}
          </button>
        </form>
      </div>
    </div>
  );
}
