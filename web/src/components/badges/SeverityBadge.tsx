import type { Severity } from '../../api/types';
import { SEVERITIES, SEVERITY_LEVELS } from '../../domain/severity';
import { cx } from '../../lib/cx';

const PIP_HEIGHTS = ['h-1.5', 'h-2', 'h-2.5', 'h-3'] as const;

/** Severity as ascending pips plus its name; the same treatment everywhere. */
export function SeverityBadge({ severity }: { severity: Severity }) {
  const meta = SEVERITIES[severity];
  return (
    <span
      className="inline-flex items-center gap-1.5 text-xs whitespace-nowrap text-ink-secondary"
      title={`${meta.label} severity (score weight ${meta.weight})`}
    >
      <span aria-hidden className="flex h-3 items-end gap-px">
        {PIP_HEIGHTS.slice(0, SEVERITY_LEVELS).map((height, index) => (
          <span
            key={height}
            className={cx(
              'w-1 rounded-[1px]',
              height,
              index < meta.level ? 'bg-ink-secondary' : 'bg-border',
            )}
          />
        ))}
      </span>
      {meta.label}
    </span>
  );
}
