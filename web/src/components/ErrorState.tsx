import { CircleAlert, RefreshCw } from 'lucide-react';
import { isApiError, toProblem } from '../api/client';
import { Button } from './Button';

interface ErrorStateProps {
  error: unknown;
  onRetry?: () => void;
  retrying?: boolean;
}

/** Shows RFC 9457 problem details (title, detail, field errors) with an optional retry. */
export function ErrorState({ error, onRetry, retrying = false }: ErrorStateProps) {
  const problem = toProblem(error);
  const status = isApiError(error) ? error.status : undefined;
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
