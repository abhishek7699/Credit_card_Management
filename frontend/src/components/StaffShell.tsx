import type { ReactNode } from 'react';
import { NavLink } from 'react-router-dom';
import { useAppDispatch } from '../store';
import { logout } from '../features/staff-session/staffSessionSlice';
import { btnGhost } from './ui';

const APP_NAME = 'Hotel Payment App';

interface StaffShellProps {
  children: ReactNode;
}

/** The only chrome /staff has: an app name header, the one working nav link
 *  (Room Board), and sign-out. Sidebar controls are full-height touch targets. */
export function StaffShell({ children }: StaffShellProps) {
  const dispatch = useAppDispatch();

  return (
    <div className="flex h-screen overflow-hidden bg-gray-50">
      <aside className="flex w-48 shrink-0 flex-col border-r border-gray-200 bg-white sm:w-56">
        <div className="flex items-center gap-3 border-b border-gray-200 px-4 py-4">
          <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-lg bg-indigo-600 text-sm font-bold text-white">
            HP
          </div>
          <p className="text-base font-semibold text-gray-900">{APP_NAME}</p>
        </div>

        <nav className="flex-1 space-y-1 p-3">
          <NavLink
            to="/staff"
            end
            className={({ isActive }) =>
              `${btnGhost} w-full font-medium ${isActive ? 'bg-indigo-50 text-indigo-700' : ''}`
            }
          >
            Room Board
          </NavLink>
        </nav>

        <div className="border-t border-gray-200 p-3">
          <button type="button" onClick={() => dispatch(logout())} className={`${btnGhost} w-full`}>
            Sign out
          </button>
        </div>
      </aside>

      <main className="flex-1 overflow-y-auto">{children}</main>
    </div>
  );
}
