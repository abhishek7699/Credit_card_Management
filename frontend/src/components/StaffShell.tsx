import type { ReactNode } from 'react';
import { NavLink } from 'react-router-dom';
import { useAppDispatch } from '../store';
import { logout } from '../features/staff-session/staffSessionSlice';

const APP_NAME = 'Hotel Payment App';

interface StaffShellProps {
  children: ReactNode;
}

/** The only chrome /staff has: an app name header, the one working nav link
 *  (Room Board), and sign-out — replaces the old owner-dashboard prototype's
 *  DashboardLayout, which linked to pages that no longer exist. */
export function StaffShell({ children }: StaffShellProps) {
  const dispatch = useAppDispatch();

  return (
    <div className="flex h-screen overflow-hidden bg-gray-50">
      <aside className="flex w-48 shrink-0 flex-col border-r border-gray-200 bg-white sm:w-56">
        <div className="flex items-center gap-3 border-b border-gray-100 px-3 py-4">
          <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-indigo-600 text-xs font-bold text-white">
            HP
          </div>
          <p className="truncate text-sm font-semibold text-gray-900">{APP_NAME}</p>
        </div>

        <nav className="flex-1 space-y-1 px-2 py-4">
          <NavLink
            to="/staff"
            end
            className={({ isActive }) =>
              `block rounded-lg px-3 py-2 text-sm transition-colors ${
                isActive
                  ? 'bg-indigo-50 font-medium text-indigo-700'
                  : 'text-gray-600 hover:bg-gray-100 hover:text-gray-900'
              }`
            }
          >
            Room Board
          </NavLink>
        </nav>

        <div className="border-t border-gray-100 px-3 py-4">
          <button
            type="button"
            onClick={() => dispatch(logout())}
            className="w-full rounded-lg px-3 py-2 text-left text-sm text-gray-600 hover:bg-gray-100 hover:text-gray-900"
          >
            Sign out
          </button>
        </div>
      </aside>

      <main className="flex-1 overflow-y-auto">{children}</main>
    </div>
  );
}
