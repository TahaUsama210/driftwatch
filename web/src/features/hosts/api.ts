import { keepPreviousData, useMutation, useQuery } from '@tanstack/react-query';
import { api, unwrap, unwrapEmpty } from '../../api/client';
import { queryKeys } from '../../api/queryKeys';
import type { HostListQuery } from '../../api/types';
import { LIVE_REFRESH_MS } from '../../lib/config';

/** Scan history depth fetched for the score chart and the drift scan pickers. */
export const SCAN_HISTORY_LIMIT = 200;

export function useHosts(query: HostListQuery) {
  return useQuery({
    queryKey: queryKeys.hosts.list(query),
    queryFn: ({ signal }) => unwrap(api.GET('/api/v1/hosts', { params: { query }, signal })),
    refetchInterval: LIVE_REFRESH_MS,
    placeholderData: keepPreviousData,
  });
}

export function useHost(hostId: string) {
  return useQuery({
    queryKey: queryKeys.hosts.detail(hostId),
    queryFn: ({ signal }) =>
      unwrap(api.GET('/api/v1/hosts/{hostId}', { params: { path: { hostId } }, signal })),
    refetchInterval: LIVE_REFRESH_MS,
  });
}

export function useHostScans(hostId: string, limit = SCAN_HISTORY_LIMIT) {
  return useQuery({
    queryKey: queryKeys.hosts.scans(hostId, limit),
    queryFn: ({ signal }) =>
      unwrap(
        api.GET('/api/v1/hosts/{hostId}/scans', {
          params: { path: { hostId }, query: { limit } },
          signal,
        }),
      ),
    refetchInterval: LIVE_REFRESH_MS,
  });
}

/**
 * A scan's full results. Scans are immutable once stored, so this never goes stale.
 * When the caller moves on to a newer scan, the previous one stays on screen until the
 * new results arrive, so a live host page does not flash a skeleton on every scan.
 */
export function useScan(scanId: string | undefined) {
  return useQuery({
    queryKey: queryKeys.scans.detail(scanId ?? ''),
    queryFn: ({ signal }) =>
      unwrap(
        api.GET('/api/v1/scans/{scanId}', { params: { path: { scanId: scanId ?? '' } }, signal }),
      ),
    enabled: scanId !== undefined,
    staleTime: Infinity,
    placeholderData: keepPreviousData,
  });
}

/**
 * Compares two scans; omitted ends default to "previous vs latest" on the server. A
 * comparison pinned with `to` is immutable, but without it "latest" moves with every
 * new scan, so that default view polls like the rest of the host page.
 */
export function useHostDrift(hostId: string, range: { from?: string; to?: string }) {
  return useQuery({
    queryKey: queryKeys.hosts.drift(hostId, range),
    queryFn: ({ signal }) =>
      unwrap(
        api.GET('/api/v1/hosts/{hostId}/drift', {
          params: { path: { hostId }, query: range },
          signal,
        }),
      ),
    refetchInterval: range.to === undefined ? LIVE_REFRESH_MS : false,
    placeholderData: keepPreviousData,
  });
}

/**
 * Deletes a host with its scans, results and drift events (admin only). Cache updates
 * are left to the caller, which has to leave the host's page before its queries go.
 */
export function useDecommissionHost() {
  return useMutation({
    mutationFn: (hostId: string) =>
      unwrapEmpty(api.DELETE('/api/v1/hosts/{hostId}', { params: { path: { hostId } } })),
  });
}
