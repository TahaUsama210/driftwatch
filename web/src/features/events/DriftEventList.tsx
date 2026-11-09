import { ArrowRight } from 'lucide-react';
import { Link } from 'react-router';
import type { CheckStatus, DriftEvent } from '../../api/types';
import { CheckStatusBadge } from '../../components/badges/CheckStatusBadge';
import { DirectionBadge } from '../../components/badges/DirectionBadge';
import { SeverityBadge } from '../../components/badges/SeverityBadge';
import { RelativeTime } from '../../components/RelativeTime';
import { LoadingRegion, Skeleton } from '../../components/Skeleton';
import { CHECK_STATUSES } from '../../domain/checkStatus';

export function StatusTransition({ from, to }: { from: CheckStatus; to: CheckStatus }) {
  return (
    <span className="inline-flex items-center gap-1">
      <CheckStatusBadge status={from} />
      <ArrowRight aria-hidden className="size-3 text-ink-muted" />
      <span className="sr-only">
        changed from {CHECK_STATUSES[from].label} to {CHECK_STATUSES[to].label}
      </span>
      <CheckStatusBadge status={to} />
    </span>
  );
}

interface DriftEventListProps {
  events: readonly DriftEvent[];
  /** Hide the host column when the list is already scoped to one host. */
  showHost?: boolean;
}

export function DriftEventList({ events, showHost = true }: DriftEventListProps) {
  return (
    <ol className="@container divide-y divide-border">
      {events.map((event) => (
        <li
          key={event.id}
          className="grid grid-cols-[minmax(0,1fr)_auto] items-start gap-x-4 gap-y-1.5 px-4 py-3 @lg:grid-cols-[6.5rem_minmax(0,1fr)_auto]"
        >
          <div className="col-span-2 @lg:col-span-1">
            <DirectionBadge direction={event.direction} />
          </div>
          <div className="min-w-0 space-y-1">
            <p className="font-medium break-words text-ink">
              <Link to={`/checks/${event.checkId}`} className="hover:underline">
                {event.title}
              </Link>
            </p>
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-ink-secondary">
              {showHost ? (
                <Link
                  to={`/hosts/${event.hostId}`}
                  className="font-mono text-ink-secondary hover:text-ink hover:underline"
                >
                  {event.hostname}
                </Link>
              ) : null}
              <StatusTransition from={event.fromStatus} to={event.toStatus} />
              <SeverityBadge severity={event.severity} />
            </div>
          </div>
          <RelativeTime
            value={event.detectedAt}
            className="pt-0.5 text-xs whitespace-nowrap text-ink-muted"
          />
        </li>
      ))}
    </ol>
  );
}

export function DriftEventListSkeleton({ rows = 5 }: { rows?: number }) {
  return (
    <LoadingRegion label="Loading drift events">
      <ul className="divide-y divide-border">
        {Array.from({ length: rows }, (_, index) => (
          <li key={index} className="flex items-start gap-4 px-4 py-3">
            <Skeleton className="h-5 w-20" />
            <div className="flex-1 space-y-2">
              <Skeleton className="h-4 w-3/5" />
              <Skeleton className="h-4 w-2/5" />
            </div>
          </li>
        ))}
      </ul>
    </LoadingRegion>
  );
}
