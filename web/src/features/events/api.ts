import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { api, unwrap } from '../../api/client';
import { queryKeys } from '../../api/queryKeys';
import type { DriftEventQuery } from '../../api/types';
import { LIVE_REFRESH_MS } from '../../lib/config';

/** Recent drift events, newest first. Polls so new drift appears without a reload. */
export function useDriftEvents(query: DriftEventQuery) {
  return useQuery({
    queryKey: queryKeys.events.list(query),
    queryFn: ({ signal }) => unwrap(api.GET('/api/v1/events', { params: { query }, signal })),
    refetchInterval: LIVE_REFRESH_MS,
    placeholderData: keepPreviousData,
  });
}
