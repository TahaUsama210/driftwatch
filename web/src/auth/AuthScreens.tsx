import { CircleAlert, LoaderCircle, LogIn, LogOut, ShieldX } from 'lucide-react';
import { useState } from 'react';
import { Button } from '../components/Button';
import { CenteredPage } from '../components/CenteredPage';
import { useAuth } from './AuthContext';
import type { AuthNotice, AuthUser } from './types';

/** While the session is being restored or the sign-in callback is processed. */
export function AuthSplash() {
  return (
    <CenteredPage title="Signing in" showThemeToggle={false}>
      <p role="status" className="flex items-center gap-2 text-ink-secondary">
        <LoaderCircle aria-hidden className="size-4 motion-safe:animate-spin" />
        Checking your session…
      </p>
    </CenteredPage>
  );
}

/**
 * The signed-out landing. Sign-in only ever starts from the button, never on render,
 * so a failing identity provider cannot cause a redirect loop.
 */
export function SignedOutLanding({ notice }: { notice?: AuthNotice | undefined }) {
  const auth = useAuth();
  const [redirecting, setRedirecting] = useState(false);
  return (
    <CenteredPage title="Sign in">
      <h1 className="text-lg font-semibold tracking-tight text-ink">Sign in to Driftwatch</h1>
      <p className="mt-1 text-ink-secondary">
        Compliance posture, score history and drift for your Linux fleet.
      </p>
      {notice ? <Notice notice={notice} /> : null}
      <Button
        variant="primary"
        className="mt-5 w-full"
        disabled={redirecting}
        onClick={() => {
          setRedirecting(true);
          void auth.signIn().finally(() => {
            setRedirecting(false);
          });
        }}
      >
        <LogIn aria-hidden className="size-4" />
        {redirecting ? 'Redirecting…' : 'Sign in'}
      </Button>
      <p className="mt-4 text-xs text-ink-muted">
        {auth.kind === 'mock'
          ? 'Mock mode: signing in uses a demo account. No identity provider is involved.'
          : 'You sign in with the Driftwatch identity provider (Keycloak). Driftwatch never sees your password.'}
      </p>
    </CenteredPage>
  );
}

/** Signed in, but the account holds no Driftwatch role. */
export function NoAccessPage({ user }: { user: AuthUser }) {
  const auth = useAuth();
  return (
    <CenteredPage title="No access">
      <div className="flex gap-3">
        <ShieldX aria-hidden className="mt-0.5 size-5 shrink-0 text-critical-ink" />
        <div className="min-w-0">
          <h1 className="font-semibold text-ink">Insufficient permissions</h1>
          <p className="mt-1 text-ink-secondary">
            You are signed in as <strong className="font-medium text-ink">{user.username}</strong>,
            but this account has no Driftwatch role. Ask an administrator to grant it the{' '}
            <code className="font-mono text-xs">viewer</code> role.
          </p>
        </div>
      </div>
      <Button className="mt-5 w-full" onClick={() => void auth.signOut()}>
        <LogOut aria-hidden className="size-4" />
        Sign out
      </Button>
    </CenteredPage>
  );
}

function Notice({ notice }: { notice: AuthNotice }) {
  return (
    <div
      role="alert"
      className="mt-4 flex gap-2.5 rounded-md border border-warning/40 bg-warning-soft p-3"
    >
      <CircleAlert aria-hidden className="mt-0.5 size-4 shrink-0 text-warning-ink" />
      <div className="min-w-0">
        <p className="font-medium text-ink">{notice.title}</p>
        {notice.detail ? <p className="mt-0.5 text-ink-secondary">{notice.detail}</p> : null}
      </div>
    </div>
  );
}
