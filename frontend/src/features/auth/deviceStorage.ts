const STORAGE_KEY = 'cg_device';

export interface StoredDevice {
  email: string;
  pinHash: string;
}

export function getStoredDevice(): StoredDevice | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    return JSON.parse(raw) as StoredDevice;
  } catch {
    return null;
  }
}

export function setStoredDevice(email: string, pinHash: string): void {
  localStorage.setItem(STORAGE_KEY, JSON.stringify({ email, pinHash }));
}

export function clearStoredDevice(): void {
  localStorage.removeItem(STORAGE_KEY);
}
