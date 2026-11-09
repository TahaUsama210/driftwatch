import { Server } from 'lucide-react';
import { Link, useParams } from 'react-router';
import { isApiError } from '../../api/client';
import type { CheckDetail, CheckHostStatus, CheckStatus } from '../../api/types';
import { CheckStatusBadge } from '../../components/badges/CheckStatusBadge';
import { SeverityBadge } from '../../components/badges/SeverityBadge';
import { Breadcrumbs } from '../../components/Breadcrumbs';
import { Card } from '../../components/Card';
import { StatusBar, StatusLegend } from '../../components/charts/StatusBar';
import { EmptyState } from '../../components/EmptyState';
import { PageHeader } from '../../components/PageHeader';
import { QueryState } from '../../components/QueryState';
import { RelativeTime } from '../../components/RelativeTime';
import { SkeletonRows } from '../../components/Skeleton';
import { StatusCounts } from '../../components/StatusCounts';
import { CATEGORY_LABELS } from '../../domain/category';
import { CHECK_STATUS_ORDER, isFailing } from '../../domain/checkStatus';
import { cx } from '../../lib/cx';
import { NotFound } from '../not-found/NotFoundPage';
import { useCheck } from './api';
import { sortFailuresFirst } from './sortFailuresFirst';

const ALL_STATUSES: readonly CheckStatus[] = CHECK_STATUS_ORDER;
const TH = 'px-4 py-2.5 text-left text-xs font-medium whitespace-nowrap text-ink-secondary';

export function CheckDetailPage() {
  const { checkId = '' } = useParams();
  const check = useCheck(checkId);

  if (isApiError(check.error) && check.error.status === 404) {
    return (
      <NotFound
        title="Check not found"
        detail="This check is not in the catalog. It may have been renamed, or the link is wrong."
      />
    );
  }

  return (
    <QueryState query={check} loading={<SkeletonRows rows={6} label="Loading check" />}>
      {(data) => <CheckDetailView check={data} />}
    </QueryState>
  );
}

function CheckDetailView({ check }: { check: CheckDetail }) {
  const hosts = sortFailuresFirst(check.hosts);
  return (
    <>
      <PageHeader
        title={check.title}
        breadcrumbs={
          <Breadcrumbs items={[{ label: 'Checks', to: '/checks' }, { label: check.id }]} />
        }
        description={
          <span className="flex flex-wrap items-center gap-x-3 gap-y-1">
            <code className="font-mono text-xs">{check.id}</code>
            <span>{CATEGORY_LABELS[check.category]}</span>
            <SeverityBadge severity={check.severity} />
          </span>
        }
      />
      <div className="space-y-6">
        <div className="space-y-3 rounded-lg border border-border bg-surface p-4">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <StatusCounts counts={check.counts} statuses={ALL_STATUSES} />
            <StatusLegend />
          </div>
          <StatusBar counts={check.counts} />
        </div>
        <Card title="Hosts" description="Status on each host's latest scan, failures first" flush>
          {hosts.length === 0 ? (
            <EmptyState icon={Server} title="No host has reported this check yet" />
          ) : (
            <HostStatusTable hosts={hosts} />
          )}
        </Card>
      </div>
    </>
  );
}

function HostStatusTable({ hosts }: { hosts: readonly CheckHostStatus[] }) {
  return (
    <div className="relative overflow-x-auto">
      <table className="w-full min-w-[52rem] border-collapse">
        <caption className="sr-only">Hosts and their status for this check</caption>
        <thead className="bg-sunken">
          <tr>
            <th scope="col" className={TH}>
              Host
            </th>
            <th scope="col" className={TH}>
              Status
            </th>
            <th scope="col" className={TH}>
              Expected
            </th>
            <th scope="col" className={TH}>
              Actual
            </th>
            <th scope="col" className={TH}>
              Evidence
            </th>
            <th scope="col" className={TH}>
              Scanned
            </th>
          </tr>
        </thead>
        <tbody className="divide-y divide-border">
          {hosts.map((host) => (
            <tr
              key={host.hostId}
              className={cx('align-top', isFailing(host.status) && 'bg-critical-soft/40')}
            >
              <td className="px-4 py-2.5 whitespace-nowrap">
                <Link
                  to={`/hosts/${host.hostId}`}
                  className="font-medium text-ink hover:text-accent hover:underline"
                >
                  {host.hostname}
                </Link>
              </td>
              <td className="px-4 py-2.5">
                <CheckStatusBadge status={host.status} />
              </td>
              <td className="px-4 py-2.5">
                <Mono value={host.expected} />
              </td>
              <td className="px-4 py-2.5">
                <Mono value={host.actual} />
              </td>
              <td className="max-w-md px-4 py-2.5">
                {host.evidence ? (
                  <span className="font-mono text-xs break-words whitespace-pre-wrap text-ink-secondary">
                    {host.evidence}
                  </span>
                ) : (
                  <span className="text-xs text-ink-muted">Not reported</span>
                )}
              </td>
              <td className="px-4 py-2.5 whitespace-nowrap text-ink-secondary">
                <RelativeTime value={host.finishedAt} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Mono({ value }: { value: string | null | undefined }) {
  return value ? (
    <code className="font-mono text-xs break-all text-ink">{value}</code>
  ) : (
    <span className="text-xs text-ink-muted">—</span>
  );
}
