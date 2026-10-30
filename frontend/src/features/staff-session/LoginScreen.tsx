import { useState } from 'react';
import { useAppDispatch } from '../../store';
import { useStaffLoginMutation } from './staffAuthApi';
import { login } from './staffSessionSlice';
import type { StaffRole } from '../../types/staff';

const ROLES: { value: StaffRole; label: string }[] = [
  { value: 'FRONT_DESK', label: 'Front Desk' },
  { value: 'HOUSEKEEPING', label: 'Housekeeping' },
];

export function LoginScreen() {
  const dispatch = useAppDispatch();
  const [role, setRole] = useState<StaffRole>('FRONT_DESK');
  const [staffLogin, { isLoading, isError }] = useStaffLoginMutation();

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    try {
      const result = await staffLogin(role).unwrap();
      dispatch(login({ role, token: result.token }));
    } catch {
      // isError is managed by RTK Query; nothing else to do here.
    }
  }

  return (
    <main className="mx-auto flex min-h-screen max-w-sm flex-col justify-center px-4">
      <h1 className="mb-6 text-2xl font-bold text-gray-900">Staff Sign In</h1>

      {isError && (
        <div role="alert" className="mb-4 rounded border border-red-300 bg-red-50 p-3 text-sm text-red-700">
          Sign-in failed. Please try again.
        </div>
      )}

      <form onSubmit={handleSubmit} className="space-y-4">
        <fieldset>
          <legend className="block text-sm font-medium text-gray-700">Role</legend>
          <div className="mt-2 space-y-2">
            {ROLES.map((r) => (
              <label key={r.value} className="flex items-center gap-2 text-sm text-gray-900">
                <input
                  type="radio"
                  name="role"
                  value={r.value}
                  checked={role === r.value}
                  onChange={() => setRole(r.value)}
                />
                {r.label}
              </label>
            ))}
          </div>
        </fieldset>

        <button
          type="submit"
          disabled={isLoading}
          className="w-full rounded bg-blue-600 px-3 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-50"
        >
          {isLoading ? 'Signing in…' : 'Sign In'}
        </button>
      </form>
    </main>
  );
}
