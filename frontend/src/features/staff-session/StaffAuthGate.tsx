import type { ReactNode } from 'react';
import { useAppSelector } from '../../store';
import { LoginScreen } from './LoginScreen';

interface Props {
  children: ReactNode;
}

/** Gates the whole app against staffSessionSlice — the old PIN device-pairing
 *  flow this was originally kept separate from has since been removed. */
export function StaffAuthGate({ children }: Props) {
  const status = useAppSelector((s) => s.staffSession.status);

  if (status === 'authenticated') return <>{children}</>;

  return <LoginScreen />;
}
