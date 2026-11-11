import { Fragment, type ReactNode } from 'react';
import { useAuthState } from './AuthContext';
import { AuthSplash, NoAccessPage, SignedOutLanding } from './AuthScreens';
import { hasRole } from './roles';

/**
 * Renders the app only for a signed-in user who may read it. The subtree is keyed by
 * the user, so another account (or signing out and back in) starts with a fresh query
 * cache and router: one user's data can never be shown to the next.
 */
export function AuthGate({ children }: { children: ReactNode }) {
  const state = useAuthState();
  switch (state.status) {
    case 'loading':
      return <AuthSplash />;
    case 'signed-out':
      return <SignedOutLanding notice={state.notice} />;
    case 'signed-in':
      if (!hasRole(state.user, 'viewer')) return <NoAccessPage user={state.user} />;
      return <Fragment key={state.user.id}>{children}</Fragment>;
  }
}
