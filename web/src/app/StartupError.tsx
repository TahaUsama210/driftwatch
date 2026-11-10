import { CircleAlert, RefreshCw } from 'lucide-react';
import { Button } from '../components/Button';
import { CenteredPage } from '../components/CenteredPage';

/** Shown when the app cannot start at all, typically because /config.json is missing. */
export function StartupError({ error }: { error: unknown }) {
  return (
    <CenteredPage title="Configuration error" showThemeToggle={false}>
      <div role="alert" className="flex gap-3">
        <CircleAlert aria-hidden className="mt-0.5 size-5 shrink-0 text-critical-ink" />
        <div className="min-w-0 space-y-1">
          <h1 className="font-semibold text-ink">Driftwatch could not start</h1>
          <p className="text-ink-secondary">
            Its runtime configuration could not be loaded, so it does not know where to sign you in.
            If this persists, the deployment is missing or misconfiguring{' '}
            <code className="font-mono text-xs">/config.json</code>.
          </p>
          {error instanceof Error ? (
            <p className="font-mono text-xs break-words text-ink-muted">{error.message}</p>
          ) : null}
        </div>
      </div>
      <Button
        className="mt-5 w-full"
        onClick={() => {
          window.location.reload();
        }}
      >
        <RefreshCw aria-hidden className="size-4" />
        Try again
      </Button>
    </CenteredPage>
  );
}
