import type { DriftDirection } from '../api/types';
import type { Tone } from './tone';

interface DirectionMeta {
  label: string;
  description: string;
  tone: Tone;
  rank: number;
}

export const DRIFT_DIRECTIONS: Record<DriftDirection, DirectionMeta> = {
  REGRESSED: {
    label: 'Regressed',
    description: 'Was passing or not applicable, now failing',
    tone: 'critical',
    rank: 0,
  },
  RESOLVED: { label: 'Resolved', description: 'Was failing, now passing', tone: 'good', rank: 1 },
  CHANGED: { label: 'Changed', description: 'Any other status change', tone: 'neutral', rank: 2 },
};

export const DRIFT_DIRECTION_ORDER = [
  'REGRESSED',
  'RESOLVED',
  'CHANGED',
] as const satisfies readonly DriftDirection[];
