import { describe, expect, it } from 'vitest';
import {
  formatAxisTime,
  formatDateTime,
  formatRelativeTime,
  formatScore,
  formatShortDateTime,
  pluralize,
} from './format';

const NOW = Date.parse('2026-11-03T14:05:30Z');
const SECOND = 1000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

describe('formatRelativeTime', () => {
  it.each([
    [0, 'just now'],
    [-9 * SECOND, 'just now'],
    [4 * SECOND, 'just now'],
    [-42 * SECOND, '42 seconds ago'],
    [-90 * SECOND, '1 minute ago'],
    [-5 * MINUTE, '5 minutes ago'],
    [-90 * MINUTE, '1 hour ago'],
    [-26 * HOUR, 'yesterday'],
    [-3 * DAY, '3 days ago'],
    [-15 * DAY, '2 weeks ago'],
    [5 * MINUTE, 'in 5 minutes'],
  ])('renders an offset of %i ms as "%s"', (offset, expected) => {
    expect(formatRelativeTime(NOW + offset, NOW)).toBe(expected);
  });

  it('accepts ISO strings', () => {
    expect(formatRelativeTime('2026-11-03T14:00:30Z', NOW)).toBe('5 minutes ago');
  });
});

describe('absolute timestamps', () => {
  it('formats the full timestamp with seconds and zone on a 24-hour clock', () => {
    expect(formatDateTime('2026-11-03T14:05:30Z', 'UTC')).toBe('Nov 3, 2026, 14:05:30 UTC');
  });

  it('formats a compact timestamp for labels', () => {
    expect(formatShortDateTime('2026-11-03T04:05:30Z', 'UTC')).toBe('Nov 3, 04:05:30');
  });

  it.each([
    [HOUR, '14:05'],
    [36 * HOUR, '14:05'],
    [7 * DAY, 'Nov 3'],
  ])('labels axis ticks for a %i ms span as "%s"', (span, expected) => {
    expect(formatAxisTime(NOW, span, 'UTC')).toBe(expected);
  });
});

describe('formatScore', () => {
  it.each([
    [92.34, '92.3'],
    [100, '100.0'],
    [0, '0.0'],
    [null, '—'],
    [undefined, '—'],
  ])('formats %s as "%s"', (score, expected) => {
    expect(formatScore(score)).toBe(expected);
  });
});

describe('pluralize', () => {
  it.each([
    [0, '0 hosts'],
    [1, '1 host'],
    [2, '2 hosts'],
    [1200, '1,200 hosts'],
  ])('pluralizes %i', (count, expected) => {
    expect(pluralize(count, 'host')).toBe(expected);
  });

  it('accepts an irregular plural', () => {
    expect(pluralize(3, 'entry', 'entries')).toBe('3 entries');
  });
});
