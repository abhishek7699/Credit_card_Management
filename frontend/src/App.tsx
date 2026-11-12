import { lazy, Suspense } from 'react';
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import { ErrorBoundary } from './components/ErrorBoundary';
import { LoadingSpinner } from './components/LoadingSpinner';
import { StaffShell } from './components/StaffShell';
import { StaffAuthGate } from './features/staff-session/StaffAuthGate';

const RoomBoard = lazy(() => import('./features/room-board/RoomBoard'));

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
          <Route path="/staff/*" element={<StaffApp />} />
          {/* /staff is the only app — every other path (including /) redirects here. */}
          <Route path="*" element={<Navigate to="/staff" replace />} />
        </Routes>
      </ErrorBoundary>
    </BrowserRouter>
  );
}
