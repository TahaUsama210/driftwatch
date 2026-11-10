import { createContext, use, useSyncExternalStore } from 'react';
import type { AuthController, AuthState, AuthUser } from './types';

/** Provided once at the top of the tree: `<AuthContext value={auth}>`. */
export const AuthContext = createContext<AuthController | undefined>(undefined);

export function useAuth(): AuthController {
  const auth = use(AuthContext);
  if (!auth) throw new Error('useAuth must be used inside <AuthContext>');
  return auth;
}

export function useAuthState(): AuthState {
  const auth = useAuth();
  return useSyncExternalStore(auth.subscribe, auth.getState);
}

/** The signed-in user. Only valid below <AuthGate>, which renders nothing else signed out. */
export function useCurrentUser(): AuthUser {
  const state = useAuthState();
  if (state.status !== 'signed-in') throw new Error('useCurrentUser needs a signed-in user');
  return state.user;
}
