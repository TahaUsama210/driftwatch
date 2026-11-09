import { useParams } from 'react-router';
import { isApiError } from '../../../api/client';
import type { Host } from '../../../api/types';
import { HostStatusBadge } from '../../../components/badges/HostStatusBadge';
import { ScoreBadge } from '../../../components/badges/ScoreBadge';
import { Breadcrumbs } from '../../../components/Breadcrumbs';
import { PageHeader } from '../../../components/PageHeader';
import { QueryState } from '../../../components/QueryState';
import { LoadingRegion, Skeleton } from '../../../components/Skeleton';
import { NotFound } from '../../not-found/NotFoundPage';
import { useHost } from '../api';
import { DriftPanel } from './DriftPanel';
import { HostEvents } from './HostEvents';
import { HostFacts } from './HostFacts';
import { LatestResults } from './LatestResults';
import { ScoreHistoryCard } from './ScoreHistoryCard';

export function HostDetailPage() {
  const { hostId = '' } = useParams();
  const host = useHost(hostId);

  if (isApiError(host.error) && host.error.status === 404) {
    return (
      <NotFound
        title="Host not found"
        detail="No enrolled host has this ID. It may have been removed, or the link is wrong."
      />
    );
  }

  return (
    <QueryState query={host} loading={<HostDetailSkeleton />}>
      {(data) => <HostDetail host={data} />}
    </QueryState>
  );
}

function HostDetail({ host }: { host: Host }) {
  return (
    <>
      <PageHeader
        title={host.hostname}
        breadcrumbs={
          <Breadcrumbs items={[{ label: 'Hosts', to: '/hosts' }, { label: host.hostname }]} />
        }
        description={host.os.prettyName}
        actions={
          <>
            <HostStatusBadge status={host.status} />
            <ScoreBadge score={host.latestScan?.score} />
          </>
        }
      />
      <div className="space-y-6">
        <div className="grid gap-6 lg:grid-cols-3">
          <HostFacts host={host} />
          <div className="min-w-0 lg:col-span-2">
            <ScoreHistoryCard hostId={host.id} />
          </div>
        </div>
        <DriftPanel hostId={host.id} />
        <div className="grid gap-6 lg:grid-cols-3">
          <div className="min-w-0 lg:col-span-2">
            <LatestResults scanId={host.latestScan?.id} />
          </div>
          <HostEvents hostId={host.id} />
        </div>
      </div>
    </>
  );
}

function HostDetailSkeleton() {
  return (
    <LoadingRegion label="Loading host">
      <div className="space-y-6">
        <div className="space-y-2">
          <Skeleton className="h-3 w-32" />
          <Skeleton className="h-7 w-64" />
        </div>
        <div className="grid gap-6 lg:grid-cols-3">
          <Skeleton className="h-64" />
          <Skeleton className="h-64 lg:col-span-2" />
        </div>
      </div>
    </LoadingRegion>
  );
}
