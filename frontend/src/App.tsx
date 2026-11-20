import { lazy, Suspense } from 'react';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { ErrorBoundary } from './components/ErrorBoundary';
import { LoadingSpinner } from './components/LoadingSpinner';
import { StaffShell } from './components/StaffShell';
import { StaffAuthGate } from './features/staff-session/StaffAuthGate';

const RoomBoard = lazy(() => import('./features/room-board/RoomBoard'));
const FrontOffice = lazy(() => import('./features/front-office/FrontOffice'));
const BookingConfirmation = lazy(() => import('./features/front-office/BookingConfirmation'));

function StaffApp() {
  return (
    <StaffAuthGate>
      <StaffShell>
        <Suspense fallback={<LoadingSpinner />}>
          <RoomBoard />
        </Suspense>
      </StaffShell>
    </StaffAuthGate>
  );
}

export default function App() {
  return (
    <BrowserRouter>
      <ErrorBoundary>
        <Routes>
          {/* Guest front office (public, no login) at /. */}
          <Route
            path="/"
            element={
              <Suspense fallback={<LoadingSpinner />}>
                <FrontOffice />
              </Suspense>
            }
          />
          <Route
            path="/booked/:bookingId"
            element={
              <Suspense fallback={<LoadingSpinner />}>
                <BookingConfirmation />
              </Suspense>
            }
          />
          {/* Staff app, unchanged, at /staff. */}
          <Route path="/staff/*" element={<StaffApp />} />
          {/* Any other path goes back to the front office. */}
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </ErrorBoundary>
    </BrowserRouter>
  );
}
