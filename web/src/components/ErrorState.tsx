import { CircleAlert, LogIn, RefreshCw, ShieldX } from 'lucide-react';
import { isApiError, toProblem } from '../api/client';
import { useAuth, useAuthState } from '../auth/AuthContext';
import { roleLabel } from '../auth/roles';
import { Button } from './Button';

interface ErrorStateProps {
  error: unknown;
  onRetry?: () => void;
  retrying?: boolean;
}

/**
 * Shows RFC 9457 problem details (title, detail, field errors) with an optional retry.
 * 401 and 403 get their own states, because retrying cannot fix either.
 */
export function ErrorState({ error, onRetry, retrying = false }: ErrorStateProps) {
  const problem = toProblem(error);
  const status = isApiError(error) ? error.status : undefined;
  if (status === 401) return <SessionExpiredState />;
  if (status === 403) return <ForbiddenState detail={problem.detail} />;
  return (
    <div
      role="alert"
      className="flex flex-col items-start gap-3 rounded-md border border-critical/30 bg-critical-soft p-4"
    >
      <div className="flex gap-3">
        <CircleAlert aria-hidden className="mt-0.5 size-5 shrink-0 text-critical-ink" />
        <div className="min-w-0 space-y-1">
          <p className="font-semibold text-ink">{problem.title}</p>
          {problem.detail ? <p className="text-ink-secondary">{problem.detail}</p> : null}
          {problem.errors && problem.errors.length > 0 ? (
            <ul className="list-disc space-y-0.5 pl-4 text-ink-secondary">
              {problem.errors.map((fieldError) => (
                <li key={`${fieldError.field}:${fieldError.message}`}>
                  <code className="font-mono text-xs">{fieldError.field}</code>:{' '}
                  {fieldError.message}
                </li>
              ))}
            </ul>
          ) : null}
          {status === undefined ? null : (
            <p className="font-mono text-xs text-ink-muted">HTTP {status}</p>
          )}
        </div>
      </div>
      {onRetry ? (
        <Button onClick={onRetry} disabled={retrying} size="sm">
          <RefreshCw
            aria-hidden
            className={retrying ? 'size-3.5 motion-safe:animate-spin' : 'size-3.5'}
          />
          {retrying ? 'Retrying…' : 'Retry'}
        </Button>
      ) : null}
    </div>
  );
}

/** The API refused the request although the user is signed in (403). */
export function ForbiddenState({ detail }: { detail?: string | undefined }) {
  const state = useAuthState();
  const role = state.status === 'signed-in' ? roleLabel(state.user) : undefined;
  return (
    <div role="alert" className="flex gap-3 rounded-md border border-border bg-sunken p-4">
      <ShieldX aria-hidden className="mt-0.5 size-5 shrink-0 text-critical-ink" />
      <div className="min-w-0 space-y-1">
        <p className="font-semibold text-ink">Insufficient permissions</p>
        <p className="text-ink-secondary">{detail ?? 'Your account is not allowed to do this.'}</p>
        <p className="text-ink-secondary">
          {role ? `Your role: ${role}. ` : ''}Ask an administrator if you need access.
        </p>
        <p className="font-mono text-xs text-ink-muted">HTTP 403</p>
      </div>
    </div>
  );
}

/**
 * The API rejected the session even after a renewal, and the automatic re-sign-in was
 * suppressed because one just happened. The person decides when to try again.
 */
export function SessionExpiredState() {
  const auth = useAuth();
  return (
    <div
      role="alert"
      className="flex flex-col items-start gap-3 rounded-md border border-warning/40 bg-warning-soft p-4"
    >
      <div className="space-y-1">
        <p className="font-semibold text-ink">Your session is no longer valid</p>
        <p className="text-ink-secondary">
          The API did not accept your sign-in. Sign in again to continue.
        </p>
        <p className="font-mono text-xs text-ink-muted">HTTP 401</p>
      </div>
      <Button size="sm" onClick={() => void auth.signIn()}>
        <LogIn aria-hidden className="size-3.5" />
        Sign in again
      </Button>
    </div>
  );
}
