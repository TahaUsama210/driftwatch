import { ChevronRight, ListChecks } from 'lucide-react';
import { useId, useState } from 'react';
import { Link } from 'react-router';
import type { CheckResult } from '../../../api/types';
import { CheckStatusBadge } from '../../../components/badges/CheckStatusBadge';
import { SeverityBadge } from '../../../components/badges/SeverityBadge';
import { Card } from '../../../components/Card';
import { EmptyState } from '../../../components/EmptyState';
import { QueryState } from '../../../components/QueryState';
import { RelativeTime } from '../../../components/RelativeTime';
import { SegmentedControl } from '../../../components/SegmentedControl';
import { SkeletonRows } from '../../../components/Skeleton';
import { CATEGORY_LABELS } from '../../../domain/category';
import { CHECK_STATUSES, CHECK_STATUS_ORDER, isFailing } from '../../../domain/checkStatus';
import { cx } from '../../../lib/cx';
import { useScan } from '../api';
import { groupResults, type StatusFilter } from './groupResults';

export function LatestResults({ scanId }: { scanId: string | undefined }) {
  const scan = useScan(scanId);
  const [filter, setFilter] = useState<StatusFilter>('ALL');

  if (scanId === undefined) {
    return (
      <Card title="Latest results">
        <EmptyState icon={ListChecks} title="No scans yet">
          Results appear after this host submits its first scan.
        </EmptyState>
      </Card>
    );
  }

  const results = scan.data?.results ?? [];
  const options = [
    { value: 'ALL' as const, label: 'All', count: results.length },
    ...CHECK_STATUS_ORDER.map((status) => ({
      value: status,
      label: CHECK_STATUSES[status].shortLabel,
      count: results.filter((result) => result.status === status).length,
    })),
  ];

  return (
    <Card
      title="Latest results"
      description={
        scan.data ? (
          <>
            Scan finished <RelativeTime value={scan.data.finishedAt} />
          </>
        ) : undefined
      }
      actions={
        <SegmentedControl
          label="Filter by status"
          options={options}
          value={filter}
          onChange={setFilter}
        />
      }
      flush
    >
      <QueryState query={scan} loading={<SkeletonRows rows={8} label="Loading scan results" />}>
        {(data) => {
          const groups = groupResults(data.results, filter);
          if (groups.length === 0) {
            return <EmptyState icon={ListChecks} title="No results with this status" />;
          }
          return (
            <div className="divide-y divide-border">
              {groups.map((group) => (
                <section key={group.category} aria-labelledby={`results-${group.category}`}>
                  <h3
                    id={`results-${group.category}`}
                    className="bg-sunken px-4 py-1.5 text-xs font-semibold tracking-wide text-ink-secondary uppercase"
                  >
                    {CATEGORY_LABELS[group.category]}
                  </h3>
                  <ul className="divide-y divide-border">
                    {group.results.map((result) => (
                      <ResultRow key={result.checkId} result={result} />
                    ))}
                  </ul>
                </section>
              ))}
            </div>
          );
        }}
      </QueryState>
    </Card>
  );
}

function ResultRow({ result }: { result: CheckResult }) {
  const [open, setOpen] = useState(false);
  const panelId = useId();
  return (
    <li>
      <button
        type="button"
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => {
          setOpen((value) => !value);
        }}
        className={cx(
          'flex w-full cursor-pointer items-center gap-3 px-4 py-2.5 text-left hover:bg-sunken',
          isFailing(result.status) && 'bg-critical-soft/40',
        )}
      >
        <ChevronRight
          aria-hidden
          className={cx('size-4 shrink-0 text-ink-muted transition-transform', open && 'rotate-90')}
        />
        <span className="w-14 shrink-0">
          <CheckStatusBadge status={result.status} />
        </span>
        <span className="min-w-0 flex-1">
          <span className="block break-words text-ink">{result.title}</span>
          <span className="block font-mono text-xs break-all text-ink-muted">{result.checkId}</span>
        </span>
        <span className="hidden sm:inline">
          <SeverityBadge severity={result.severity} />
        </span>
      </button>
      <div id={panelId} hidden={!open} className="space-y-3 px-4 pt-1 pb-4 sm:pl-[6.75rem]">
        <span className="sm:hidden">
          <SeverityBadge severity={result.severity} />
        </span>
        <dl className="grid gap-3 sm:grid-cols-2">
          <Value label="Expected" value={result.expected} />
          <Value label="Actual" value={result.actual} />
          <div className="sm:col-span-2">
            <Value label="Evidence" value={result.evidence} block />
          </div>
        </dl>
        <Link
          to={`/checks/${result.checkId}`}
          className="inline-block text-xs font-medium text-accent hover:underline"
        >
          See this check across the fleet
        </Link>
      </div>
    </li>
  );
}

function Value({
  label,
  value,
  block = false,
}: {
  label: string;
  value: string | null | undefined;
  block?: boolean;
}) {
  return (
    <div className="min-w-0">
      <dt className="text-xs text-ink-muted">{label}</dt>
      <dd className="mt-0.5">
        {value === null || value === undefined || value === '' ? (
          <span className="text-ink-muted">Not reported</span>
        ) : block ? (
          <pre className="rounded-md border border-border bg-sunken p-2 font-mono text-xs break-words whitespace-pre-wrap text-ink">
            {value}
          </pre>
        ) : (
          <code className="font-mono text-xs break-all text-ink">{value}</code>
        )}
      </dd>
    </div>
  );
}
