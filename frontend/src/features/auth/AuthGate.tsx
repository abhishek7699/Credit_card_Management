import { type ReactNode, useState } from 'react';
import { useAppSelector } from '../../store';
import { getStoredDevice, clearStoredDevice, type StoredDevice } from './deviceStorage';
import { PinRegistration } from './PinRegistration';
import { PinUnlock } from './PinUnlock';

interface Props {
  children: ReactNode;
}

export function AuthGate({ children }: Props) {
  const isAuthenticated = useAppSelector((s) => s.auth.isAuthenticated);
  const [device, setDevice] = useState<StoredDevice | null>(getStoredDevice);

  if (isAuthenticated) return <>{children}</>;

  if (device) {
    return (
      <PinUnlock
        device={device}
        onSignOut={() => {
          clearStoredDevice();
          setDevice(null);
        }}
      />
    );
  }

  return (
    <PinRegistration
      onRegistered={() => setDevice(getStoredDevice())}
    />
  );
}
