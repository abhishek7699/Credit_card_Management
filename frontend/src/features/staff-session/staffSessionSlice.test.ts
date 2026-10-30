import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import reducer, { login, logout, sessionExpired } from './staffSessionSlice';

const STORAGE_KEY = 'staffSession';

describe('staffSessionSlice', () => {
  beforeEach(() => {
    sessionStorage.clear();
  });

  test('login sets role/token/status and persists to sessionStorage', () => {
    const state = reducer(undefined, login({ role: 'FRONT_DESK', token: 'tok-1' }));
    expect(state).toEqual({ role: 'FRONT_DESK', token: 'tok-1', status: 'authenticated' });
    expect(JSON.parse(sessionStorage.getItem(STORAGE_KEY)!)).toEqual({
      role: 'FRONT_DESK',
      token: 'tok-1',
    });
  });

  test('sessionExpired clears role/token, sets status expired, clears sessionStorage', () => {
    const loggedIn = reducer(undefined, login({ role: 'HOUSEKEEPING', token: 'tok-2' }));
    const state = reducer(loggedIn, sessionExpired());
    expect(state).toEqual({ role: null, token: null, status: 'expired' });
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  test('logout clears role/token, sets status anonymous, clears sessionStorage', () => {
    const loggedIn = reducer(undefined, login({ role: 'FRONT_DESK', token: 'tok-3' }));
    const state = reducer(loggedIn, logout());
    expect(state).toEqual({ role: null, token: null, status: 'anonymous' });
    expect(sessionStorage.getItem(STORAGE_KEY)).toBeNull();
  });

  // Re-imports the same specifier after vi.resetModules() so the module's
  // initialState() runs again fresh against whatever sessionStorage/mocks the
  // test has set up — resetModules() drops the registry entry, so the next
  // dynamic import re-executes top-level initialization (unlike a fake
  // query-string per import, this resolves fine under `tsc -b` too).
  async function freshModule() {
    vi.resetModules();
    return import('./staffSessionSlice');
  }

  test('a prior sessionStorage entry hydrates status authenticated on construction', async () => {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ role: 'FRONT_DESK', token: 'tok-4' }));
    const fresh = await freshModule();
    const state = fresh.default(undefined, { type: '@@INIT' });
    expect(state).toEqual({ role: 'FRONT_DESK', token: 'tok-4', status: 'authenticated' });
  });

  test('no prior sessionStorage entry hydrates status anonymous on construction', async () => {
    const fresh = await freshModule();
    const state = fresh.default(undefined, { type: '@@INIT' });
    expect(state).toEqual({ role: null, token: null, status: 'anonymous' });
  });

  test('a corrupt sessionStorage entry is treated as absent', async () => {
    sessionStorage.setItem(STORAGE_KEY, 'not-json');
    const fresh = await freshModule();
    const state = fresh.default(undefined, { type: '@@INIT' });
    expect(state).toEqual({ role: null, token: null, status: 'anonymous' });
  });

  test('a malformed (non-string field) sessionStorage entry is treated as absent', async () => {
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify({ role: 'FRONT_DESK', token: 42 }));
    const fresh = await freshModule();
    const state = fresh.default(undefined, { type: '@@INIT' });
    expect(state).toEqual({ role: null, token: null, status: 'anonymous' });
  });

  describe('sessionStorage throwing (e.g. private-browsing mode)', () => {
    afterEach(() => {
      vi.restoreAllMocks();
    });

    test('a throwing getItem is treated as no stored session, not a crash', async () => {
      vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
        throw new Error('blocked');
      });
      const fresh = await freshModule();
      const state = fresh.default(undefined, { type: '@@INIT' });
      expect(state).toEqual({ role: null, token: null, status: 'anonymous' });
    });

    test('a throwing setItem on login does not prevent the state update', () => {
      vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
        throw new Error('blocked');
      });
      const state = reducer(undefined, login({ role: 'FRONT_DESK', token: 'tok-5' }));
      expect(state).toEqual({ role: 'FRONT_DESK', token: 'tok-5', status: 'authenticated' });
    });

    test('a throwing removeItem on logout does not prevent the state update', () => {
      const loggedIn = reducer(undefined, login({ role: 'FRONT_DESK', token: 'tok-6' }));
      vi.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
        throw new Error('blocked');
      });
      const state = reducer(loggedIn, logout());
      expect(state).toEqual({ role: null, token: null, status: 'anonymous' });
    });
  });
});
