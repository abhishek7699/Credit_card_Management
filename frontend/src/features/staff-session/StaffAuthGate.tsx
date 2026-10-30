import type { ReactNode } from 'react';
import { useAppSelector } from '../../store';
import { LoginScreen } from './LoginScreen';

interface Props {
  children: ReactNode;
}

/** Entirely separate from the existing (PIN-based) AuthGate/authSlice — this gates
 *  only the /staff subtree, against staffSessionSlice, not the PIN device-pairing
 *  flow (research.md §4). */
export function StaffAuthGate({ children }: Props) {
  const status = useAppSelector((s) => s.staffSession.status);

  if (status === 'authenticated') return <>{children}</>;

  return <LoginScreen />;
}
