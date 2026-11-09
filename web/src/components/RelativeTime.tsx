import { formatDateTime, formatRelativeTime } from '../lib/format';
import { useNow } from '../lib/useNow';

/** Relative time that stays current; the absolute timestamp is on hover and in `dateTime`. */
export function RelativeTime({ value, className }: { value: string; className?: string }) {
  const now = useNow();
  return (
    <time dateTime={value} title={formatDateTime(value)} className={className}>
      {formatRelativeTime(value, now)}
    </time>
  );
}
