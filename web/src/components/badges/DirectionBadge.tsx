import type { DriftDirection } from '../../api/types';
import { DRIFT_DIRECTIONS } from '../../domain/drift';
import { Badge } from './Badge';
import { DIRECTION_ICONS } from './statusIcons';

export function DirectionBadge({ direction }: { direction: DriftDirection }) {
  const meta = DRIFT_DIRECTIONS[direction];
  return (
    <Badge tone={meta.tone} icon={DIRECTION_ICONS[direction]} title={meta.description}>
      {meta.label}
    </Badge>
  );
}
