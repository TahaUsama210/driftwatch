import type { CheckStatus, StatusCounts } from '../api/types';
import type { Tone } from './tone';

interface CheckStatusMeta {
  label: string;
  shortLabel: string;
  tone: Tone;
  /** Lower sorts first: what needs attention comes before what does not. */
  rank: number;
}

export const CHECK_STATUSES: Record<CheckStatus, CheckStatusMeta> = {
  FAIL: { label: 'Fail', shortLabel: 'Fail', tone: 'critical', rank: 0 },
  ERROR: { label: 'Error', shortLabel: 'Error', tone: 'serious', rank: 1 },
  PASS: { label: 'Pass', shortLabel: 'Pass', tone: 'good', rank: 2 },
  NOT_APPLICABLE: { label: 'Not applicable', shortLabel: 'N/A', tone: 'neutral', rank: 3 },
};

/** Display order for status breakdowns. */
export const CHECK_STATUS_ORDER = [
  'PASS',
  'FAIL',
  'ERROR',
  'NOT_APPLICABLE',
] as const satisfies readonly CheckStatus[];

/** FAIL and ERROR both count against the score (an unverifiable control is not compliant). */
export function isFailing(status: CheckStatus): boolean {
  return status === 'FAIL' || status === 'ERROR';
}

export function countFor(counts: StatusCounts, status: CheckStatus): number {
  switch (status) {
    case 'PASS':
      return counts.pass;
    case 'FAIL':
      return counts.fail;
    case 'ERROR':
      return counts.error;
    case 'NOT_APPLICABLE':
      return counts.notApplicable;
  }
}

export function totalCount(counts: StatusCounts): number {
  return counts.pass + counts.fail + counts.error + counts.notApplicable;
}

/** "2 pass, 1 fail, 0 error, 1 not applicable": an accessible name for a breakdown. */
export function describeStatusCounts(counts: StatusCounts): string {
  return CHECK_STATUS_ORDER.map(
    (status) => `${countFor(counts, status)} ${CHECK_STATUSES[status].label.toLowerCase()}`,
  ).join(', ');
}
