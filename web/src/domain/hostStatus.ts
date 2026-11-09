import type { HostStatus } from '../api/types';
import type { Tone } from './tone';

export const HOST_STATUSES: Record<HostStatus, { label: string; description: string; tone: Tone }> =
  {
    ONLINE: { label: 'Online', description: 'Reported recently', tone: 'good' },
    STALE: { label: 'Stale', description: 'No report within the stale window', tone: 'warning' },
  };

export const HOST_STATUS_ORDER = ['ONLINE', 'STALE'] as const satisfies readonly HostStatus[];
