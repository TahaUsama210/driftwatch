import type { HostStatus } from '../../api/types';
import { HOST_STATUSES } from '../../domain/hostStatus';
import { Badge } from './Badge';
import { HOST_STATUS_ICONS } from './statusIcons';

export function HostStatusBadge({ status }: { status: HostStatus }) {
  const meta = HOST_STATUSES[status];
  return (
    <Badge tone={meta.tone} icon={HOST_STATUS_ICONS[status]} title={meta.description}>
      {meta.label}
    </Badge>
  );
}
