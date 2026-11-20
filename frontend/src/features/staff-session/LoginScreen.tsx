import { useState } from 'react';
import { useAppDispatch } from '../../store';
import { useStaffLoginMutation } from './staffAuthApi';
import { login } from './staffSessionSlice';
import { alertError, btnPrimary, fieldLabel } from '../../components/ui';
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
    <main className="mx-auto flex min-h-screen w-full max-w-md flex-col justify-center gap-6 bg-gray-50 px-6">
      <div>
        <p className="text-sm font-medium text-gray-500">Hotel Payment App</p>
        <h1 className="mt-1 text-3xl font-bold text-gray-900">Staff Sign In</h1>
      </div>

      {isError && (
        <div role="alert" className={alertError}>
          Sign-in didn&apos;t work. Check your connection and tap Sign In again.
        </div>
      )}

      <form onSubmit={handleSubmit} className="space-y-5">
        <fieldset className="space-y-3">
          <legend className={fieldLabel}>Choose your role</legend>
          {ROLES.map((r) => (
            <label
              key={r.value}
              className="flex min-h-12 cursor-pointer items-center gap-3 rounded-lg border border-gray-300 bg-white px-4 text-base text-gray-900 has-[:checked]:border-indigo-600 has-[:checked]:bg-indigo-50"
            >
              <input
                type="radio"
                name="role"
                value={r.value}
                checked={role === r.value}
                onChange={() => setRole(r.value)}
                className="h-5 w-5 accent-indigo-600"
              />
              {r.label}
            </label>
          ))}
        </fieldset>

        <button type="submit" disabled={isLoading} className={btnPrimary}>
          {isLoading ? 'Signing in…' : 'Sign In'}
        </button>
      </form>
    </main>
  );
}
