import { describe, expect, it } from 'vitest';
import { isCategory } from './category';
import { describeStatusCounts, isFailing, totalCount } from './checkStatus';
import { SCORE_THRESHOLDS, SCORE_TIERS, scoreTier } from './score';

describe('scoreTier', () => {
  it.each([
    [100, 'good'],
    [SCORE_THRESHOLDS.good, 'good'],
    [89.9, 'warning'],
    [SCORE_THRESHOLDS.warning, 'warning'],
    [69.9, 'poor'],
    [0, 'poor'],
    [null, 'unscored'],
    [undefined, 'unscored'],
    [Number.NaN, 'unscored'],
  ] as const)('places a score of %s in the %s tier', (score, tier) => {
    expect(scoreTier(score)).toBe(tier);
  });

  it('describes each band from the same thresholds', () => {
    expect(SCORE_TIERS.good.description).toBe('90 or higher');
    expect(SCORE_TIERS.warning.description).toBe('70 to below 90');
    expect(SCORE_TIERS.poor.description).toBe('Below 70');
  });
});

describe('check status rules', () => {
  it.each([
    ['FAIL', true],
    ['ERROR', true],
    ['PASS', false],
    ['NOT_APPLICABLE', false],
  ] as const)('treats %s as failing: %s', (status, failing) => {
    expect(isFailing(status)).toBe(failing);
  });

  it('describes a breakdown in display order for screen readers', () => {
    const counts = { pass: 2, fail: 1, error: 0, notApplicable: 1 };
    expect(describeStatusCounts(counts)).toBe('2 pass, 1 fail, 0 error, 1 not applicable');
    expect(totalCount(counts)).toBe(4);
  });
});

describe('isCategory', () => {
  it('accepts catalog categories and rejects anything else', () => {
    expect(isCategory('ssh')).toBe(true);
    expect(isCategory('toString')).toBe(false);
    expect(isCategory(null)).toBe(false);
  });
});
