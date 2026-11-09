import type { StatusCounts } from '../../api/types';
import {
  CHECK_STATUS_ORDER,
  CHECK_STATUSES,
  countFor,
  describeStatusCounts,
  totalCount,
} from '../../domain/checkStatus';
import { cx } from '../../lib/cx';
import { formatCount } from '../../lib/format';
import { TONE_CLASSES } from '../badges/toneStyles';

interface StatusBarProps {
  counts: StatusCounts;
  className?: string;
}

/**
 * A thin segmented bar of one whole (hosts or checks) split by status. It sits beside
 * the figures it summarises, so it carries no text of its own; segments are separated
 * by a 2px surface gap rather than outlines.
 */
export function StatusBar({ counts, className }: StatusBarProps) {
  const total = totalCount(counts);
  return (
    <div
      role="img"
      aria-label={describeStatusCounts(counts)}
      className={cx('flex h-2 w-full gap-0.5 overflow-hidden rounded-full bg-sunken', className)}
    >
      {total === 0
        ? null
        : CHECK_STATUS_ORDER.map((status) => {
            const count = countFor(counts, status);
            if (count === 0) return null;
            const meta = CHECK_STATUSES[status];
            return (
              <span
                key={status}
                title={`${meta.label}: ${formatCount(count)}`}
                className={cx('h-full min-w-1', TONE_CLASSES[meta.tone].fill)}
                style={{ flexGrow: count, flexBasis: 0 }}
              />
            );
          })}
    </div>
  );
}

/** One legend for every status bar on a page: swatch plus name. */
export function StatusLegend({ className }: { className?: string }) {
  return (
    <ul
      className={cx(
        'flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-ink-secondary',
        className,
      )}
    >
      {CHECK_STATUS_ORDER.map((status) => {
        const meta = CHECK_STATUSES[status];
        return (
          <li key={status} className="flex items-center gap-1.5">
            <span aria-hidden className={cx('h-2 w-3 rounded-sm', TONE_CLASSES[meta.tone].fill)} />
            {meta.label}
          </li>
        );
      })}
    </ul>
  );
}
