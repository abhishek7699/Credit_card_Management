import { createSlice, type PayloadAction } from '@reduxjs/toolkit';
import type { StaffRole } from '../../types/staff';

const STORAGE_KEY = 'staffSession';

export type StaffSessionStatus = 'anonymous' | 'authenticated' | 'expired';

export interface StaffSessionState {
  role: StaffRole | null;
  token: string | null;
  status: StaffSessionStatus;
}

interface StoredSession {
  role: StaffRole;
  token: string;
}

function readStoredSession(): StoredSession | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as StoredSession;
    if (typeof parsed.role === 'string' && typeof parsed.token === 'string') {
      return parsed;
    }
    return null;
  } catch {
    return null;
  }
}

function writeStoredSession(session: StoredSession): void {
  try {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(session));
  } catch {
    // sessionStorage can throw in some private-browsing modes — losing
    // persistence across a reload is an acceptable degradation, not a crash.
  }
}

function clearStoredSession(): void {
  try {
    sessionStorage.removeItem(STORAGE_KEY);
  } catch {
    // see writeStoredSession
  }
}

function initialState(): StaffSessionState {
  const stored = readStoredSession();
  if (stored) {
    return { role: stored.role, token: stored.token, status: 'authenticated' };
  }
  return { role: null, token: null, status: 'anonymous' };
}

const staffSessionSlice = createSlice({
  name: 'staffSession',
  initialState: initialState(),
  reducers: {
    login: (state, action: PayloadAction<{ role: StaffRole; token: string }>) => {
      state.role = action.payload.role;
      state.token = action.payload.token;
      state.status = 'authenticated';
      writeStoredSession(action.payload);
    },
    sessionExpired: (state) => {
      state.role = null;
      state.token = null;
      state.status = 'expired';
      clearStoredSession();
    },
    logout: (state) => {
      state.role = null;
      state.token = null;
      state.status = 'anonymous';
      clearStoredSession();
    },
  },
});

export const { login, sessionExpired, logout } = staffSessionSlice.actions;
export default staffSessionSlice.reducer;
