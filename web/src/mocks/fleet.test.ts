import { describe, expect, it } from 'vitest';
import type { CheckStatus } from '../api/types';
import { CATALOG } from './catalog';
import { computeScore, createDemoFleet, DEMO_HOSTS, driftDirection, MockApiError } from './fleet';

/** docs/CONTRACTS.md: only a missing sshd_config or /etc/crontab makes a check NOT_APPLICABLE. */
const mayBeNotApplicable = (checkId: string) =>
  checkId.startsWith('ssh.') || checkId === 'filesystem.crontab_permissions';

/** The mock API must follow the same domain rules as the real one (docs/CONTRACTS.md). */
describe('mock fleet contract rules', () => {
  it.each([
    [
      [
        { status: 'PASS', severity: 'high' },
        { status: 'FAIL', severity: 'medium' },
      ],
      60,
    ],
    [
      [
        { status: 'PASS', severity: 'low' },
        { status: 'ERROR', severity: 'low' },
      ],
      50,
    ],
    [
      [
        { status: 'PASS', severity: 'medium' },
        { status: 'FAIL', severity: 'low' },
      ],
      66.7,
    ],
    [
      [
        { status: 'PASS', severity: 'low' },
        { status: 'NOT_APPLICABLE', severity: 'critical' },
      ],
      100,
    ],
    [[{ status: 'NOT_APPLICABLE', severity: 'high' }], null],
  ] as const)('scores %j as %s', (statuses, expected) => {
    expect(computeScore(statuses)).toBe(expected);
  });

  it.each([
    ['PASS', 'FAIL', 'REGRESSED'],
    ['NOT_APPLICABLE', 'ERROR', 'REGRESSED'],
    ['FAIL', 'PASS', 'RESOLVED'],
    ['ERROR', 'PASS', 'RESOLVED'],
    ['FAIL', 'ERROR', 'CHANGED'],
    ['PASS', 'NOT_APPLICABLE', 'CHANGED'],
    ['PASS', 'PASS', null],
  ] as const)('classifies %s -> %s as %s', (from, to, expected) => {
    expect(driftDirection(from, to)).toBe(expected);
  });

  it('produces live drift from the flapping host, newest first', () => {
    const now = Date.parse('2026-11-03T14:00:00Z');
    const fleet = createDemoFleet({ anchor: now, now: () => now });

    const events = fleet.events({ limit: 20 });

    expect(events).toHaveLength(20);
    const times = events.map((event) => Date.parse(event.detectedAt));
    expect(times).toEqual([...times].sort((a, b) => b - a));
    expect(events.some((event) => event.hostname === 'host-alpine-drifting')).toBe(true);
    expect(
      fleet.events({ direction: 'RESOLVED', limit: 5 }).every((e) => e.direction === 'RESOLVED'),
    ).toBe(true);
  });

  it('reports NOT_APPLICABLE only for checks the contract allows', () => {
    const notApplicable = DEMO_HOSTS.flatMap((host) => {
      const statuses: (readonly [string, CheckStatus | undefined])[] = [
        ...Object.entries(host.baseline ?? {}),
        ...(host.changes ?? []).map((change) => [change.checkId, change.status] as const),
        ...(host.flap?.statuses ?? []).map((status) => [host.flap?.checkId ?? '', status] as const),
      ];
      return statuses.filter(([, status]) => status === 'NOT_APPLICABLE').map(([id]) => id);
    });

    expect(notApplicable.length).toBeGreaterThan(0);
    expect(notApplicable.filter((id) => !mayBeNotApplicable(id))).toEqual([]);
    expect(CATALOG.filter((check) => check.notApplicable && !mayBeNotApplicable(check.id))).toEqual(
      [],
    );
  });

  it('rejects trend requests over the point cap', () => {
    const fleet = createDemoFleet();
    expect(() => fleet.trend(720, 1)).toThrow(MockApiError);
    expect(fleet.trend(24, 15).points).toHaveLength(96);
  });
});
