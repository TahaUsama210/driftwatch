import { ChartLine } from 'lucide-react';
import { useState } from 'react';
import { useSearchParams } from 'react-router';
import { Card } from '../../components/Card';
import { ChartViewToggle, ScoreTrend, type ChartView } from '../../components/charts/ScoreTrend';
import { EmptyState } from '../../components/EmptyState';
import { QueryState } from '../../components/QueryState';
import { SegmentedControl } from '../../components/SegmentedControl';
import { LoadingRegion, Skeleton } from '../../components/Skeleton';
import { pluralize, toTimestamp } from '../../lib/format';
import { useFleetTrend } from './api';
import {
  DEFAULT_TREND_RANGE,
  parseTrendRange,
  TREND_RANGES,
  type TrendRangeKey,
} from './trendRanges';

const RANGE_OPTIONS = (Object.keys(TREND_RANGES) as TrendRangeKey[]).map((key) => ({
  value: key,
  label: TREND_RANGES[key].label,
}));

export function FleetTrendCard() {
  const [searchParams, setSearchParams] = useSearchParams();
  const rangeKey = parseTrendRange(searchParams.get('range'));
  const range = TREND_RANGES[rangeKey];
  const trend = useFleetTrend(range);
  const [view, setView] = useState<ChartView>('chart');

  return (
    <Card
      className="h-full"
      title="Fleet score trend"
      description={`${range.description}. Each bucket averages every host's most recent score.`}
      actions={
        <>
          <SegmentedControl
            label="Time range"
            options={RANGE_OPTIONS}
            value={rangeKey}
            onChange={(value) => {
              setSearchParams(
                (params) => {
                  if (value === DEFAULT_TREND_RANGE) params.delete('range');
                  else params.set('range', value);
                  return params;
                },
                { replace: true, preventScrollReset: true },
              );
            }}
          />
          <ChartViewToggle view={view} onChange={setView} />
        </>
      }
    >
      <QueryState
        query={trend}
        loading={
          <LoadingRegion label="Loading fleet trend">
            <Skeleton className="h-60 w-full" />
          </LoadingRegion>
        }
      >
        {(data) => {
          const points = data.points.map((point) => ({
            t: toTimestamp(point.bucketStart),
            score: point.averageScore,
            note: `${pluralize(point.hostCount, 'host')} reporting`,
          }));
          if (points.every((point) => point.score === null)) {
            return (
              <EmptyState icon={ChartLine} title="No scans in this range">
                Pick a longer range, or wait for the next scan to arrive.
              </EmptyState>
            );
          }
          return (
            <div className={trend.isPlaceholderData ? 'opacity-60 transition-opacity' : undefined}>
              <ScoreTrend
                points={points}
                view={view}
                label={`Fleet average score, ${range.description.toLowerCase()}`}
                noteLabel="Hosts"
                height={320}
              />
            </div>
          );
        }}
      </QueryState>
    </Card>
  );
}
