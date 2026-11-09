/**
 * Number and time formatting. Everything goes through Intl; the UI copy is English, so
 * the locale is fixed and the time zone is the viewer's own unless a caller overrides it.
 */
export const APP_LOCALE = 'en-US';

const SECOND = 1000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

const RELATIVE_UNITS: readonly (readonly [Intl.RelativeTimeFormatUnit, number])[] = [
  ['year', 365 * DAY],
  ['month', 30 * DAY],
  ['week', 7 * DAY],
  ['day', DAY],
  ['hour', HOUR],
  ['minute', MINUTE],
  ['second', SECOND],
];

/** Differences smaller than this read as "just now" (also absorbs small clock skew). */
const JUST_NOW_MS = 10 * SECOND;

const relativeFormatter = new Intl.RelativeTimeFormat(APP_LOCALE, { numeric: 'auto' });
const scoreFormatter = new Intl.NumberFormat(APP_LOCALE, {
  minimumFractionDigits: 1,
  maximumFractionDigits: 1,
});
const integerFormatter = new Intl.NumberFormat(APP_LOCALE, { maximumFractionDigits: 0 });
const pluralRules = new Intl.PluralRules(APP_LOCALE);

const dateFormatters = new Map<string, Intl.DateTimeFormat>();

function dateFormatter(options: Intl.DateTimeFormatOptions): Intl.DateTimeFormat {
  const key = JSON.stringify(options);
  let formatter = dateFormatters.get(key);
  if (!formatter) {
    formatter = new Intl.DateTimeFormat(APP_LOCALE, { hourCycle: 'h23', ...options });
    dateFormatters.set(key, formatter);
  }
  return formatter;
}

export type TimeInput = string | number | Date;

export function toTimestamp(value: TimeInput): number {
  return typeof value === 'number' ? value : new Date(value).getTime();
}

/** "just now", "42 seconds ago", "5 minutes ago", "yesterday", ... */
export function formatRelativeTime(value: TimeInput, now: number): string {
  const diff = toTimestamp(value) - now;
  if (Math.abs(diff) < JUST_NOW_MS) return 'just now';
  const [unit, size] =
    RELATIVE_UNITS.find(([, unitSize]) => Math.abs(diff) >= unitSize) ??
    (['second', SECOND] as const);
  return relativeFormatter.format(Math.trunc(diff / size), unit);
}

/** Full timestamp with seconds and time zone, e.g. "Nov 3, 2026, 14:05:30 UTC". */
export function formatDateTime(value: TimeInput, timeZone?: string): string {
  return dateFormatter({ dateStyle: 'medium', timeStyle: 'long', timeZone }).format(
    toTimestamp(value),
  );
}

/** Compact timestamp for option labels and tooltips, e.g. "Nov 3, 14:05:30". */
export function formatShortDateTime(value: TimeInput, timeZone?: string): string {
  return dateFormatter({
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    timeZone,
  }).format(toTimestamp(value));
}

/** Axis tick label: clock time for spans up to a day and a half, calendar date beyond. */
export function formatAxisTime(value: TimeInput, spanMs: number, timeZone?: string): string {
  const options: Intl.DateTimeFormatOptions =
    spanMs <= 36 * HOUR
      ? { hour: '2-digit', minute: '2-digit', timeZone }
      : { month: 'short', day: 'numeric', timeZone };
  return dateFormatter(options).format(toTimestamp(value));
}

/** Scores always show one decimal, matching the API's precision; null renders as an em dash. */
export function formatScore(score: number | null | undefined): string {
  return score === null || score === undefined ? '—' : scoreFormatter.format(score);
}

export function formatCount(value: number): string {
  return integerFormatter.format(value);
}

/** "1 host", "3 hosts". Pass `plural` for irregular nouns. */
export function pluralize(count: number, singular: string, plural = `${singular}s`): string {
  return `${formatCount(count)} ${pluralRules.select(count) === 'one' ? singular : plural}`;
}
