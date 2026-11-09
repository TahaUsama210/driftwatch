import { RefreshCw } from 'lucide-react';
import { formatDateTime, formatRelativeTime } from '../lib/format';
import { useNow } from '../lib/useNow';
import { Button } from './Button';

interface LiveIndicatorProps {
  /** Epoch ms of the last successful fetch (TanStack's `dataUpdatedAt`). */
  updatedAt: number;
  refreshing: boolean;
  /** The latest background refresh failed; the data shown is older than intended. */
  failed: boolean;
  onRefresh: () => void;
}

/** "Updated 12 seconds ago" for auto-refreshing views, plus a manual refresh. */
export function LiveIndicator({ updatedAt, refreshing, failed, onRefresh }: LiveIndicatorProps) {
  const now = useNow();
  const ready = updatedAt > 0;
  return (
    <div className="flex items-center gap-2 text-xs text-ink-secondary">
      <span className="relative flex size-2" aria-hidden>
        {failed ? (
          <span className="size-2 rounded-full bg-warning" />
        ) : (
          <>
            <span className="absolute inline-flex size-full rounded-full bg-good opacity-60 motion-safe:animate-ping" />
            <span className="relative inline-flex size-2 rounded-full bg-good" />
          </>
        )}
      </span>
      <span>
        {failed ? 'Refresh failed · ' : null}
        {ready ? (
          <>
            Updated{' '}
            <time dateTime={new Date(updatedAt).toISOString()} title={formatDateTime(updatedAt)}>
              {formatRelativeTime(updatedAt, now)}
            </time>
          </>
        ) : (
          'Loading…'
        )}
      </span>
      <Button
        size="sm"
        variant="ghost"
        onClick={onRefresh}
        disabled={refreshing}
        aria-label="Refresh now"
      >
        <RefreshCw
          aria-hidden
          className={refreshing ? 'size-3.5 motion-safe:animate-spin' : 'size-3.5'}
        />
      </Button>
    </div>
  );
}
