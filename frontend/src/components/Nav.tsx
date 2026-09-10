import { NavLink } from 'react-router-dom';
import { useAppDispatch } from '../store';
import { logout } from '../store/authSlice';

const links = [
  { to: '/', label: 'Home', end: true },
  { to: '/payment', label: 'New Payment', end: false },
  { to: '/history', label: 'Transaction History', end: false },
  { to: '/dashboard', label: 'Owner Dashboard', end: false },
] as const;

export function Nav() {
  const dispatch = useAppDispatch();

  return (
    <nav className="border-b border-gray-200 bg-white">
      <div className="mx-auto flex max-w-4xl items-center justify-between px-4 py-3">
        <div className="flex items-center gap-6">
          <span className="font-semibold text-gray-900">Card Gateway</span>
          <ul className="flex gap-4">
            {links.map(({ to, label, end }) => (
              <li key={to}>
                <NavLink
                  to={to}
                  end={end}
                  className={({ isActive }) =>
                    `text-sm ${isActive ? 'font-semibold text-blue-600' : 'text-gray-600 hover:text-gray-900'}`
                  }
                >
                  {label}
                </NavLink>
              </li>
            ))}
          </ul>
        </div>
        <button
          onClick={() => dispatch(logout())}
          className="text-sm text-gray-500 hover:text-gray-800"
        >
          Log out
        </button>
      </div>
    </nav>
  );
}
