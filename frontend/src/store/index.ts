import { configureStore, isRejectedWithValue, type Middleware } from '@reduxjs/toolkit';
import { useDispatch, useSelector } from 'react-redux';
import { api } from './api';
import authReducer from './authSlice';
import staffSessionReducer, { sessionExpired } from '../features/staff-session/staffSessionSlice';

/** Any rejected RTK Query request with a 401 ends the staff session uniformly —
 *  one place, rather than every staff feature re-checking this itself (research.md §6). */
const sessionExpiryMiddleware: Middleware = (storeApi) => (next) => (action) => {
  if (isRejectedWithValue(action) && (action.payload as { status?: number })?.status === 401) {
    storeApi.dispatch(sessionExpired());
  }
  return next(action);
};

export const store = configureStore({
  reducer: {
    [api.reducerPath]: api.reducer,
    auth: authReducer,
    staffSession: staffSessionReducer,
  },
  middleware: (getDefaultMiddleware) =>
    getDefaultMiddleware().concat(api.middleware, sessionExpiryMiddleware),
});

export type RootState = ReturnType<typeof store.getState>;
export type AppDispatch = typeof store.dispatch;

export const useAppDispatch = useDispatch.withTypes<AppDispatch>();
export const useAppSelector = useSelector.withTypes<RootState>();
