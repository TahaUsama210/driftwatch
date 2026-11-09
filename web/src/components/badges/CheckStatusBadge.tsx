import type { CheckStatus } from '../../api/types';
import { CHECK_STATUSES } from '../../domain/checkStatus';
import { Badge } from './Badge';
import { CHECK_STATUS_ICONS } from './statusIcons';

export function CheckStatusBadge({ status }: { status: CheckStatus }) {
  const meta = CHECK_STATUSES[status];
  return (
    <Badge tone={meta.tone} icon={CHECK_STATUS_ICONS[status]} title={meta.label}>
      {meta.shortLabel}
    </Badge>
  );
}
