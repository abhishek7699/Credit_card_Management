import { Suspense, type ReactNode } from 'react';
import { NavLink, Outlet } from 'react-router-dom';
import { LoadingSpinner } from './LoadingSpinner';

function HomeIcon() {
  return (
    <svg className="h-5 w-5 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.8} aria-hidden="true">
      <path strokeLinecap="round" strokeLinejoin="round" d="M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6" />
    </svg>
  );
}

function GridIcon() {
  return (
    <svg className="h-5 w-5 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.8} aria-hidden="true">
      <path strokeLinecap="round" strokeLinejoin="round" d="M4 5a1 1 0 011-1h4a1 1 0 011 1v4a1 1 0 01-1 1H5a1 1 0 01-1-1V5zm10 0a1 1 0 011-1h4a1 1 0 011 1v4a1 1 0 01-1 1h-4a1 1 0 01-1-1V5zM4 15a1 1 0 011-1h4a1 1 0 011 1v4a1 1 0 01-1 1H5a1 1 0 01-1-1v-4zm10 0a1 1 0 011-1h4a1 1 0 011 1v4a1 1 0 01-1 1h-4a1 1 0 01-1-1v-4z" />
    </svg>
  );
}

function CreditCardIcon() {
  return (
    <svg className="h-5 w-5 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.8} aria-hidden="true">
      <path strokeLinecap="round" strokeLinejoin="round" d="M3 10h18M7 15h1m4 0h1m-7 4h12a3 3 0 003-3V8a3 3 0 00-3-3H6a3 3 0 00-3 3v8a3 3 0 003 3z" />
    </svg>
  );
}

function CalendarIcon() {
  return (
    <svg className="h-5 w-5 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.8} aria-hidden="true">
      <path strokeLinecap="round" strokeLinejoin="round" d="M8 7V3m8 4V3m-9 8h10M5 21h14a2 2 0 002-2V7a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z" />
    </svg>
  );
}

function BedIcon() {
  return (
    <svg className="h-5 w-5 shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.8} aria-hidden="true">
      <path strokeLinecap="round" strokeLinejoin="round" d="M3 12h18M3 12V8a2 2 0 012-2h14a2 2 0 012 2v4M3 12v5a1 1 0 001 1h16a1 1 0 001-1v-5M7 10h10" />
    </svg>
  );
}

const NAV_ITEMS = [
  { to: '/dashboard', label: 'Home', icon: HomeIcon, end: true },
  { to: '/dashboard/floor-plan', label: 'Floor Plan', icon: GridIcon, end: false },
  { to: '/dashboard/payments', label: 'Payments', icon: CreditCardIcon, end: false },
  { to: '/dashboard/reservations', label: 'Reservations', icon: CalendarIcon, end: false },
  { to: '/dashboard/rooms', label: 'Rooms', icon: BedIcon, end: false },
] as const;

function SidebarNavItem({
  to,
  label,
  icon: Icon,
  end,
}: {
  to: string;
  label: string;
  icon: () => ReactNode;
  end: boolean;
}) {
  return (
    <NavLink
      to={to}
      end={end}
      className={({ isActive }) =>
        `flex items-center gap-3 rounded-lg px-3 py-2 text-sm transition-colors ${
          isActive
            ? 'bg-indigo-50 font-medium text-indigo-700'
            : 'text-gray-600 hover:bg-gray-100 hover:text-gray-900'
        }`
      }
    >
      <Icon />
      <span className="hidden md:inline">{label}</span>
    </NavLink>
  );
}

export function DashboardLayout() {
  return (
    <div className="flex h-screen overflow-hidden bg-gray-50">
      {/* Sidebar */}
      <aside className="flex w-16 shrink-0 flex-col border-r border-gray-200 bg-white md:w-56">
        {/* Property header */}
        <div className="flex items-center gap-3 border-b border-gray-100 px-3 py-4">
          <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-indigo-600 text-xs font-bold text-white">
            OB
          </div>
          <div className="hidden min-w-0 md:block">
            <p className="truncate text-sm font-semibold text-gray-900">Oceanview Boutique</p>
            <p className="text-xs text-gray-500">Hotel</p>
          </div>
        </div>

        {/* Nav */}
        <nav className="flex-1 space-y-1 px-2 py-4">
          {NAV_ITEMS.map(({ to, label, icon, end }) => (
            <SidebarNavItem key={to} to={to} label={label} icon={icon} end={end} />
          ))}

          <div className="my-2 border-t border-gray-100" />
        </nav>

        {/* User avatar */}
        <div className="border-t border-gray-100 px-3 py-4">
          <div className="flex items-center gap-3">
            <div className="h-8 w-8 shrink-0 rounded-full bg-indigo-100 ring-2 ring-indigo-200" />
            <div className="hidden min-w-0 md:block">
              <p className="truncate text-sm font-medium text-gray-900">Owner</p>
              <p className="text-xs text-gray-500">Admin</p>
            </div>
          </div>
        </div>
      </aside>

      {/* Main content */}
      <main className="flex-1 overflow-y-auto">
        <Suspense fallback={<LoadingSpinner />}>
          <Outlet />
        </Suspense>
      </main>
    </div>
  );
}
