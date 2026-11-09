import { ChartLine } from 'lucide-react';
import { useState } from 'react';
import { Card } from '../../../components/Card';
import { ChartViewToggle, ScoreTrend, type ChartView } from '../../../components/charts/ScoreTrend';
import { EmptyState } from '../../../components/EmptyState';
import { QueryState } from '../../../components/QueryState';
import { LoadingRegion, Skeleton } from '../../../components/Skeleton';
import { formatCount, pluralize, toTimestamp } from '../../../lib/format';
import { SCAN_HISTORY_LIMIT, useHostScans } from '../api';

export function ScoreHistoryCard({ hostId }: { hostId: string }) {
  const scans = useHostScans(hostId);
  const [view, setView] = useState<ChartView>('chart');

  return (
    <Card
      title="Score history"
      description={`Last ${formatCount(SCAN_HISTORY_LIMIT)} scans at most; a score holds until the next scan`}
      actions={<ChartViewToggle view={view} onChange={setView} />}
    >
      <QueryState
        query={scans}
        loading={
          <LoadingRegion label="Loading score history">
            <Skeleton className="h-60 w-full" />
          </LoadingRegion>
        }
      >
        {(items) => {
          if (items.length === 0) {
            return (
              <EmptyState icon={ChartLine} title="No scans yet">
                The chart fills in once this host submits its first scan.
              </EmptyState>
            );
          }
          // The API returns newest first; the chart reads left to right in time.
          const points = items
            .map((scan) => ({
              t: toTimestamp(scan.finishedAt),
              score: scan.score,
              note: pluralize(scan.counts.fail + scan.counts.error, 'failing check'),
            }))
            .reverse();
          return (
            <ScoreTrend
              points={points}
              view={view}
              curve="stepAfter"
              label="Compliance score per scan"
              noteLabel="Failing"
            />
          );
        }}
      </QueryState>
    </Card>
  );
}
