import { CircleCheck, CircleX, Clock } from 'lucide-react';
import type { ReactNode } from 'react';
import { Link } from 'react-router';
import type { FleetSummary } from '../../api/types';
import { Badge } from '../../components/badges/Badge';
import { SCORE_TIER_ICONS } from '../../components/badges/statusIcons';
import { RelativeTime } from '../../components/RelativeTime';
import { LoadingRegion, Skeleton } from '../../components/Skeleton';
import { SCORE_TIERS, scoreTier } from '../../domain/score';
import { formatCount, formatDateTime, formatScore, pluralize } from '../../lib/format';

/** Headline numbers. A <dl> keeps each label tied to its value for assistive tech. */
export function KpiTiles({ summary }: { summary: FleetSummary }) {
  const tier = scoreTier(summary.averageScore);
  const tierMeta = SCORE_TIERS[tier];
  const criticalHosts = summary.hostsWithCriticalFailures;

  return (
    <dl className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
      <Tile
        label="Fleet average score"
        value={formatScore(summary.averageScore)}
        footer={
          <Badge tone={tierMeta.tone} icon={SCORE_TIER_ICONS[tier]} title={tierMeta.description}>
            {tierMeta.label}
          </Badge>
        }
      />
      <Tile
        label="Hosts online"
        value={
          <>
            {formatCount(summary.onlineCount)}
            <span className="ml-1 text-base font-normal text-ink-muted">
              of {formatCount(summary.hostCount)}
            </span>
          </>
        }
        footer={
          summary.staleCount > 0 ? (
            <Link
              to="/hosts?status=STALE"
              className="inline-flex items-center gap-1 text-ink-secondary hover:text-ink hover:underline"
            >
              <Clock aria-hidden className="size-3.5 text-warning-ink" />
              {pluralize(summary.staleCount, 'stale host')}
            </Link>
          ) : (
            <span className="inline-flex items-center gap-1 text-ink-secondary">
              <CircleCheck aria-hidden className="size-3.5 text-good-ink" />
              Every host is reporting
            </span>
          )
        }
      />
      <Tile
        label="Hosts with critical failures"
        value={formatCount(criticalHosts)}
        footer={
          <span className="inline-flex items-center gap-1 text-ink-secondary">
            {criticalHosts > 0 ? (
              <>
                <CircleX aria-hidden className="size-3.5 text-critical-ink" />
                Failing at least one critical check
              </>
            ) : (
              <>
                <CircleCheck aria-hidden className="size-3.5 text-good-ink" />
                No critical check is failing
              </>
            )}
          </span>
        }
      />
      <Tile
        label="Last scan"
        value={summary.lastScanAt ? <RelativeTime value={summary.lastScanAt} /> : 'No scans yet'}
        footer={
          summary.lastScanAt ? (
            <span className="text-ink-secondary">{formatDateTime(summary.lastScanAt)}</span>
          ) : null
        }
      />
    </dl>
  );
}

function Tile({ label, value, footer }: { label: string; value: ReactNode; footer: ReactNode }) {
  return (
    <div className="flex min-w-0 flex-col gap-2 rounded-lg border border-border bg-surface p-4">
      <dt className="text-xs font-medium text-ink-secondary">{label}</dt>
      <dd className="text-3xl leading-none font-semibold tracking-tight text-ink first-letter:uppercase">
        {value}
      </dd>
      <dd className="text-xs">{footer}</dd>
    </div>
  );
}

export function KpiTilesSkeleton() {
  return (
    <LoadingRegion label="Loading fleet summary">
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        {Array.from({ length: 4 }, (_, index) => (
          <div key={index} className="space-y-3 rounded-lg border border-border bg-surface p-4">
            <Skeleton className="h-3 w-28" />
            <Skeleton className="h-8 w-20" />
            <Skeleton className="h-3 w-36" />
          </div>
        ))}
      </div>
    </LoadingRegion>
  );
}
