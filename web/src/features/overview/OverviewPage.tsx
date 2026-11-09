import { useQueryClient } from '@tanstack/react-query';
import { Inbox } from 'lucide-react';
import { Link } from 'react-router';
import { queryKeys } from '../../api/queryKeys';
import type { FleetSummary } from '../../api/types';
import { Card } from '../../components/Card';
import { EmptyState } from '../../components/EmptyState';
import { LiveIndicator } from '../../components/LiveIndicator';
import { PageHeader } from '../../components/PageHeader';
import { QueryState } from '../../components/QueryState';
import { EnrollAgentPanel } from '../enroll/EnrollAgentPanel';
import { useDriftEvents } from '../events/api';
import { DriftEventList, DriftEventListSkeleton } from '../events/DriftEventList';
import { useFleetSummary } from './api';
import { FleetTrendCard } from './FleetTrendCard';
import { KpiTiles, KpiTilesSkeleton } from './KpiTiles';
import { TopFailingChecks } from './TopFailingChecks';

const RECENT_EVENTS = 8;

export function OverviewPage() {
  const summary = useFleetSummary();
  const queryClient = useQueryClient();

  const refreshAll = () => {
    void queryClient.invalidateQueries({ queryKey: queryKeys.fleet.all });
    void queryClient.invalidateQueries({ queryKey: queryKeys.events.all });
  };

  return (
    <>
      <PageHeader
        title="Fleet overview"
        documentTitle="Overview"
        description="Compliance posture across every enrolled host."
        actions={
          // Still shown after a failed refresh: that is when its "Refresh failed" marker matters.
          summary.isSuccess || summary.isRefetchError ? (
            <LiveIndicator
              updatedAt={summary.dataUpdatedAt}
              refreshing={summary.isFetching}
              failed={summary.isRefetchError}
              onRefresh={refreshAll}
            />
          ) : null
        }
      />
      <QueryState query={summary} loading={<KpiTilesSkeleton />}>
        {(data) => (data.hostCount === 0 ? <EnrollAgentPanel /> : <FleetOverview summary={data} />)}
      </QueryState>
    </>
  );
}

function FleetOverview({ summary }: { summary: FleetSummary }) {
  return (
    <div className="space-y-6">
      <KpiTiles summary={summary} />
      <div className="grid gap-6 lg:grid-cols-3">
        <div className="min-w-0 lg:col-span-2">
          <FleetTrendCard />
        </div>
        <TopFailingChecks checks={summary.topFailingChecks} hostCount={summary.hostCount} />
      </div>
      <RecentDrift />
    </div>
  );
}

function RecentDrift() {
  const events = useDriftEvents({ limit: RECENT_EVENTS });
  return (
    <Card
      title="Recent drift"
      description="Live feed of checks that changed status between scans"
      actions={
        <Link to="/events" className="text-xs font-medium text-accent hover:underline">
          All events
        </Link>
      }
      flush
    >
      <QueryState query={events} loading={<DriftEventListSkeleton />}>
        {(items) =>
          items.length === 0 ? (
            <EmptyState icon={Inbox} title="No drift yet">
              Drift appears when a check changes status between two consecutive scans of a host.
            </EmptyState>
          ) : (
            <DriftEventList events={items} />
          )
        }
      </QueryState>
    </Card>
  );
}
