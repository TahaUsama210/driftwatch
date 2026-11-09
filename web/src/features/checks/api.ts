import { useQuery } from '@tanstack/react-query';
import { api, unwrap } from '../../api/client';
import { queryKeys } from '../../api/queryKeys';
import { LIVE_REFRESH_MS } from '../../lib/config';

export function useChecks() {
  return useQuery({
    queryKey: queryKeys.checks.list(),
    queryFn: ({ signal }) => unwrap(api.GET('/api/v1/checks', { signal })),
    refetchInterval: LIVE_REFRESH_MS,
  });
}

export function useCheck(checkId: string) {
  return useQuery({
    queryKey: queryKeys.checks.detail(checkId),
    queryFn: ({ signal }) =>
      unwrap(api.GET('/api/v1/checks/{checkId}', { params: { path: { checkId } }, signal })),
    refetchInterval: LIVE_REFRESH_MS,
  });
}
