import type { DriftEventQuery, FleetTrendQuery, HostListQuery } from './types';

/**
 * Every TanStack Query key in the app. Keys are hierarchical so a whole area can be
 * invalidated at once (e.g. `queryKeys.hosts.all`).
 */
const fleet = ['fleet'] as const;
const hosts = ['hosts'] as const;
const scans = ['scans'] as const;
const checks = ['checks'] as const;
const events = ['events'] as const;

export const queryKeys = {
  fleet: {
    all: fleet,
    summary: () => [...fleet, 'summary'] as const,
    trend: (query: Required<FleetTrendQuery>) => [...fleet, 'trend', query] as const,
  },
  hosts: {
    all: hosts,
    list: (query: HostListQuery) => [...hosts, 'list', query] as const,
    detail: (hostId: string) => [...hosts, 'detail', hostId] as const,
    scans: (hostId: string, limit: number) => [...hosts, 'detail', hostId, 'scans', limit] as const,
    drift: (hostId: string, range: { from?: string; to?: string }) =>
      [...hosts, 'detail', hostId, 'drift', range] as const,
  },
  scans: {
    all: scans,
    detail: (scanId: string) => [...scans, scanId] as const,
  },
  checks: {
    all: checks,
    list: () => [...checks, 'list'] as const,
    detail: (checkId: string) => [...checks, 'detail', checkId] as const,
  },
  events: {
    all: events,
    list: (query: DriftEventQuery) => [...events, 'list', query] as const,
  },
} as const;
