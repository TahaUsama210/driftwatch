import type { CheckStatus, StatusCounts as Counts } from '../api/types';
import { CHECK_STATUSES, countFor } from '../domain/checkStatus';
import { cx } from '../lib/cx';
import { formatCount } from '../lib/format';
import { CHECK_STATUS_ICONS } from './badges/statusIcons';
import { TONE_CLASSES } from './badges/toneStyles';

interface StatusCountsProps {
  counts: Counts;
  /** Statuses to show; zero counts are still shown so columns line up. */
  statuses?: readonly CheckStatus[];
}

/** Compact "✓ 18  ✕ 3  ! 1" counts with icon + number, labelled for screen readers. */
export function StatusCounts({ counts, statuses = ['PASS', 'FAIL', 'ERROR'] }: StatusCountsProps) {
  return (
    <span className="inline-flex items-center gap-3 tabular-nums">
      {statuses.map((status) => {
        const meta = CHECK_STATUSES[status];
        const Icon = CHECK_STATUS_ICONS[status];
        const count = countFor(counts, status);
        return (
          <span
            key={status}
            title={`${meta.label}: ${formatCount(count)}`}
            className={cx(
              'inline-flex items-center gap-1',
              count === 0 ? 'text-ink-muted' : 'text-ink',
            )}
          >
            <Icon
              aria-hidden
              className={cx(
                'size-3.5',
                count === 0 ? 'text-ink-muted' : TONE_CLASSES[meta.tone].ink,
              )}
            />
            {formatCount(count)}
            <span className="sr-only"> {meta.label.toLowerCase()}</span>
          </span>
        );
      })}
    </span>
  );
}
