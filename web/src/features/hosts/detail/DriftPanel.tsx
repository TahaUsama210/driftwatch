import { CircleCheck, GitCompareArrows } from 'lucide-react';
import { Link, useSearchParams } from 'react-router';
import type { DriftChange, DriftReport, ScanSummary } from '../../../api/types';
import { DirectionBadge } from '../../../components/badges/DirectionBadge';
import { SeverityBadge } from '../../../components/badges/SeverityBadge';
import { Button } from '../../../components/Button';
import { Card } from '../../../components/Card';
import { EmptyState } from '../../../components/EmptyState';
import { SelectField } from '../../../components/fields';
import { QueryState } from '../../../components/QueryState';
import { SkeletonRows } from '../../../components/Skeleton';
import { DRIFT_DIRECTION_ORDER, DRIFT_DIRECTIONS } from '../../../domain/drift';
import { cx } from '../../../lib/cx';
import { formatCount, formatScore, formatShortDateTime, toTimestamp } from '../../../lib/format';
import { StatusTransition } from '../../events/DriftEventList';
import { useHostDrift, useHostScans } from '../api';
import { sortChanges } from './sortChanges';

function scanLabel(scan: ScanSummary): string {
  return `${formatShortDateTime(scan.finishedAt)} · score ${formatScore(scan.score)}`;
}

/** Newest first, always including the scans the report actually compared. */
function scanOptions(history: readonly ScanSummary[], report: DriftReport | undefined) {
  const byId = new Map(history.map((scan) => [scan.id, scan]));
  for (const scan of [report?.fromScan, report?.toScan]) {
    if (scan) byId.set(scan.id, scan);
  }
  return [...byId.values()].sort((a, b) => toTimestamp(b.finishedAt) - toTimestamp(a.finishedAt));
}

/**
 * Compares any two scans of a host. The selection lives in the URL (`?from=&to=`) so a
 * comparison can be shared; with neither set the API compares the latest scan with
 * the one before it.
 */
export function DriftPanel({ hostId }: { hostId: string }) {
  const [searchParams, setSearchParams] = useSearchParams();
  const from = searchParams.get('from') ?? undefined;
  const to = searchParams.get('to') ?? undefined;
  const scans = useHostScans(hostId);
  const drift = useHostDrift(hostId, { from, to });

  const select = (next: { from?: string; to?: string }) => {
    setSearchParams(
      (params) => {
        for (const key of ['from', 'to'] as const) {
          const value = next[key];
          if (value) params.set(key, value);
          else params.delete(key);
        }
        return params;
      },
      { replace: true, preventScrollReset: true },
    );
  };

  const report = drift.data;
  const options = scanOptions(scans.data ?? [], report);
  const fromScan = report?.fromScan ?? null;
  const toScan = report?.toScan ?? null;
  const canCompare = options.length >= 2 && fromScan !== null && toScan !== null;

  return (
    <Card
      title="Drift between scans"
      description="Checks whose status differs between two scans of this host"
      actions={
        from || to ? (
          <Button
            size="sm"
            variant="ghost"
            onClick={() => {
              select({});
            }}
          >
            Previous vs latest
          </Button>
        ) : null
      }
      flush
    >
      {canCompare ? (
        <div className="grid gap-3 border-b border-border p-4 sm:grid-cols-2">
          <SelectField
            label="Baseline scan"
            value={fromScan.id}
            onChange={(id) => {
              select({ from: id, to: toScan.id });
            }}
          >
            {options
              .filter((scan) => toTimestamp(scan.finishedAt) < toTimestamp(toScan.finishedAt))
              .map((scan) => (
                <option key={scan.id} value={scan.id}>
                  {scanLabel(scan)}
                </option>
              ))}
          </SelectField>
          <SelectField
            label="Compared scan"
            value={toScan.id}
            onChange={(id) => {
              select({ from: fromScan.id, to: id });
            }}
          >
            {options
              .filter((scan) => toTimestamp(scan.finishedAt) > toTimestamp(fromScan.finishedAt))
              .map((scan) => (
                <option key={scan.id} value={scan.id}>
                  {scanLabel(scan)}
                </option>
              ))}
          </SelectField>
        </div>
      ) : null}
      <QueryState query={drift} loading={<SkeletonRows rows={3} label="Comparing scans" />}>
        {(data) => (
          <div className={drift.isPlaceholderData ? 'opacity-60 transition-opacity' : undefined}>
            <DriftReportView report={data} />
          </div>
        )}
      </QueryState>
    </Card>
  );
}

function DriftReportView({ report }: { report: DriftReport }) {
  if (report.toScan === null) {
    return (
      <EmptyState icon={GitCompareArrows} title="No scans yet">
        Drift is computed once this host has two scans.
      </EmptyState>
    );
  }
  if (report.fromScan === null) {
    return (
      <EmptyState icon={GitCompareArrows} title="Only one scan so far">
        The first scan is this host&apos;s baseline. Drift appears after the next scan.
      </EmptyState>
    );
  }

  const changes = sortChanges(report.changes);
  const counts = DRIFT_DIRECTION_ORDER.map((direction) => ({
    direction,
    count: changes.filter((change) => change.direction === direction).length,
  }));

  return (
    <>
      <div className="flex flex-wrap items-center gap-x-6 gap-y-2 px-4 py-3 text-xs text-ink-secondary">
        <ScoreDelta from={report.fromScan.score} to={report.toScan.score} />
        <ul className="flex flex-wrap gap-x-4 gap-y-1" aria-label="Changes by direction">
          {counts.map(({ direction, count }) => (
            <li key={direction}>
              <span className="font-semibold text-ink tabular-nums">{formatCount(count)}</span>{' '}
              {DRIFT_DIRECTIONS[direction].label.toLowerCase()}
            </li>
          ))}
        </ul>
      </div>
      {changes.length === 0 ? (
        <EmptyState icon={CircleCheck} title="No differences">
          Every check has the same status in both scans.
        </EmptyState>
      ) : (
        <ul className="divide-y divide-border border-t border-border">
          {changes.map((change) => (
            <ChangeRow key={change.checkId} change={change} />
          ))}
        </ul>
      )}
    </>
  );
}

function ScoreDelta({ from, to }: { from: number | null; to: number | null }) {
  const delta = from !== null && to !== null ? Math.round((to - from) * 10) / 10 : null;
  return (
    <p className="tabular-nums">
      Score <span className="font-semibold text-ink">{formatScore(from)}</span> →{' '}
      <span className="font-semibold text-ink">{formatScore(to)}</span>
      {delta === null || delta === 0 ? null : (
        <span className={cx('ml-1.5', delta < 0 ? 'text-critical-ink' : 'text-good-ink')}>
          ({delta > 0 ? '+' : '−'}
          {formatScore(Math.abs(delta))})
        </span>
      )}
    </p>
  );
}

function ChangeRow({ change }: { change: DriftChange }) {
  const regressed = change.direction === 'REGRESSED';
  return (
    <li
      data-direction={change.direction}
      className={cx(
        'space-y-2 border-l-2 px-4 py-3',
        regressed ? 'border-l-critical bg-critical-soft/50' : 'border-l-transparent',
      )}
    >
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5">
        <DirectionBadge direction={change.direction} />
        <Link
          to={`/checks/${change.checkId}`}
          className="font-medium break-words text-ink hover:underline"
        >
          {change.title}
        </Link>
        <SeverityBadge severity={change.severity} />
      </div>
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs">
        <StatusTransition from={change.fromStatus} to={change.toStatus} />
        <code className="font-mono break-all text-ink-muted">{change.checkId}</code>
      </div>
      {change.fromEvidence || change.toEvidence ? (
        <details className="group text-xs">
          <summary className="cursor-pointer text-ink-secondary hover:text-ink">Evidence</summary>
          <dl className="mt-2 grid gap-2 sm:grid-cols-2">
            <Evidence label="Baseline" value={change.fromEvidence} />
            <Evidence label="Compared" value={change.toEvidence} />
          </dl>
        </details>
      ) : null}
    </li>
  );
}

function Evidence({ label, value }: { label: string; value: string | null | undefined }) {
  return (
    <div className="min-w-0">
      <dt className="text-ink-muted">{label}</dt>
      <dd className="mt-0.5">
        {value ? (
          <pre className="rounded-md border border-border bg-surface p-2 font-mono break-words whitespace-pre-wrap text-ink">
            {value}
          </pre>
        ) : (
          <span className="text-ink-muted">Not reported</span>
        )}
      </dd>
    </div>
  );
}
