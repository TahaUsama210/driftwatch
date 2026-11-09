export interface TrendRange {
  label: string;
  description: string;
  hours: number;
  bucketMinutes: number;
}

/**
 * Range presets for the fleet trend. Buckets are sized for 60-100 points per range,
 * well under the API's 500-point cap.
 */
export const TREND_RANGES = {
  '1h': { label: '1h', description: 'Last hour, per minute', hours: 1, bucketMinutes: 1 },
  '24h': {
    label: '24h',
    description: 'Last 24 hours, per 15 minutes',
    hours: 24,
    bucketMinutes: 15,
  },
  '7d': { label: '7d', description: 'Last 7 days, per 2 hours', hours: 168, bucketMinutes: 120 },
  '30d': {
    label: '30d',
    description: 'Last 30 days, per 12 hours',
    hours: 720,
    bucketMinutes: 720,
  },
} as const satisfies Record<string, TrendRange>;

export type TrendRangeKey = keyof typeof TREND_RANGES;

export const DEFAULT_TREND_RANGE: TrendRangeKey = '24h';

export function parseTrendRange(value: string | null): TrendRangeKey {
  return value !== null && Object.hasOwn(TREND_RANGES, value)
    ? (value as TrendRangeKey)
    : DEFAULT_TREND_RANGE;
}
