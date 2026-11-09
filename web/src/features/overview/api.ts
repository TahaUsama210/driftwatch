import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { api, unwrap } from '../../api/client';
import { queryKeys } from '../../api/queryKeys';
import { LIVE_REFRESH_MS } from '../../lib/config';
import type { TrendRange } from './trendRanges';

export function useFleetSummary() {
  return useQuery({
    queryKey: queryKeys.fleet.summary(),
    queryFn: ({ signal }) => unwrap(api.GET('/api/v1/fleet/summary', { signal })),
    refetchInterval: LIVE_REFRESH_MS,
  });
}

export function useFleetTrend({ hours, bucketMinutes }: TrendRange) {
  const query = { hours, bucketMinutes };
  return useQuery({
    queryKey: queryKeys.fleet.trend(query),
    queryFn: ({ signal }) => unwrap(api.GET('/api/v1/fleet/trend', { params: { query }, signal })),
    refetchInterval: LIVE_REFRESH_MS,
    placeholderData: keepPreviousData,
  });
}
