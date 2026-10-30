import { lazy, Suspense } from 'react';
import { BrowserRouter, Outlet, Route, Routes } from 'react-router-dom';
import { ErrorBoundary } from './components/ErrorBoundary';
import { LoadingSpinner } from './components/LoadingSpinner';
import { Nav } from './components/Nav';
import { DashboardLayout } from './components/DashboardLayout';
import { AuthGate } from './features/auth/AuthGate';
import { StaffAuthGate } from './features/staff-session/StaffAuthGate';

const HomePage = lazy(() => import('./pages/HomePage'));
const PaymentForm = lazy(() => import('./features/payment-submission/PaymentForm'));
const TransactionStatus = lazy(() => import('./features/transaction-status/TransactionStatus'));
const TransactionHistory = lazy(() => import('./features/transaction-history/TransactionHistory'));
const DashboardHome = lazy(() => import('./features/owner-dashboard-home/DashboardHome'));
const FloorPlan = lazy(() => import('./features/owner-floor-plan/FloorPlan'));
const OwnerPayments = lazy(() => import('./features/owner-payments/OwnerPayments'));
const OwnerReservations = lazy(() => import('./features/owner-reservations/OwnerReservations'));
const RoomManagement = lazy(() => import('./features/owner-room-management/RoomManagement'));

/** Placeholder until T027 (User Story 1) wires in the real RoomBoard. */
function StaffHome() {
  return <p className="p-6 text-gray-900">Signed in.</p>;
}

function WithTopNav() {
  return (
    <>
      <Nav />
      <Suspense fallback={<LoadingSpinner />}>
        <Outlet />
      </Suspense>
    </>
  );
}

function LegacyPrototypeApp() {
  return (
    <AuthGate>
      <Routes>
        <Route element={<WithTopNav />}>
          <Route path="/" element={<HomePage />} />
          <Route path="/payment" element={<PaymentForm />} />
          <Route path="/transactions/:id" element={<TransactionStatus />} />
          <Route path="/history" element={<TransactionHistory />} />
        </Route>
        <Route path="/dashboard" element={<DashboardLayout />}>
          <Route index element={<DashboardHome />} />
          <Route path="floor-plan" element={<FloorPlan />} />
          <Route path="payments" element={<OwnerPayments />} />
          <Route path="reservations" element={<OwnerReservations />} />
          <Route path="rooms" element={<RoomManagement />} />
        </Route>
      </Routes>
    </AuthGate>
  );
}

export default function App() {
  return (
    <BrowserRouter>
      <ErrorBoundary>
        <Routes>
          {/* Sibling to, not nested in, the PIN-gated prototype above — its own
              session/gate, no interaction with AuthGate/authSlice (research.md §4). */}
          <Route
            path="/staff/*"
            element={
              <Suspense fallback={<LoadingSpinner />}>
                <StaffAuthGate>
                  <StaffHome />
                </StaffAuthGate>
              </Suspense>
            }
          />
          <Route path="/*" element={<LegacyPrototypeApp />} />
        </Routes>
      </ErrorBoundary>
    </BrowserRouter>
  );
}
