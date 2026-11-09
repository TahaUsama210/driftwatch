import type { Tone } from './tone';

/** Score bands, inclusive lower bounds. The only place these numbers live. */
export const SCORE_THRESHOLDS = { good: 90, warning: 70 } as const;

export type ScoreTier = 'good' | 'warning' | 'poor' | 'unscored';

interface ScoreTierMeta {
  label: string;
  description: string;
  tone: Tone;
}

export const SCORE_TIERS: Record<ScoreTier, ScoreTierMeta> = {
  good: { label: 'Good', description: `${SCORE_THRESHOLDS.good} or higher`, tone: 'good' },
  warning: {
    label: 'Needs attention',
    description: `${SCORE_THRESHOLDS.warning} to below ${SCORE_THRESHOLDS.good}`,
    tone: 'warning',
  },
  poor: { label: 'Poor', description: `Below ${SCORE_THRESHOLDS.warning}`, tone: 'critical' },
  unscored: { label: 'Not scored', description: 'No applicable checks yet', tone: 'neutral' },
};

export function scoreTier(score: number | null | undefined): ScoreTier {
  if (score === null || score === undefined || Number.isNaN(score)) return 'unscored';
  if (score >= SCORE_THRESHOLDS.good) return 'good';
  if (score >= SCORE_THRESHOLDS.warning) return 'warning';
  return 'poor';
}
