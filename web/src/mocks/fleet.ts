import type {
  CheckDetail,
  CheckHostStatus,
  CheckResult,
  CheckStatus,
  CheckSummary,
  DriftChange,
  DriftDirection,
  DriftEvent,
  DriftReport,
  FleetSummary,
  FleetTrend,
  Host,
  HostListQuery,
  HostPage,
  OsInfo,
  ScanDetail,
  ScanSummary,
  StatusCounts,
} from '../api/types';
import { CATALOG, type MockCheck } from './catalog';

/**
 * A deterministic, in-memory model of the demo fleet that answers every read endpoint
 * of the contract. Nothing is stored per scan: host k's scan finishes at
 * `firstScanAt + k * interval`, and each check's status at a moment comes from the
 * host's posture timeline (baseline, dated changes, and an optional flapping check).
 * Scans, scores, drift and trends are all derived from that, so as the clock moves new
 * scans and drift events appear exactly as they would from live agents.
 */

const SECOND = 1000;
const MINUTE = 60 * SECOND;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;

/** Mirrors the API default `DRIFTWATCH_STALE_AFTER=PT5M`. */
export const STALE_AFTER_MS = 5 * MINUTE;
/** The API rejects trend requests that would exceed this many points. */
export const MAX_TREND_POINTS = 500;

const WEIGHTS = { low: 1, medium: 2, high: 3, critical: 5 } as const;
const SCAN_DURATION_MS = 1400;
const RECEIVE_DELAY_MS = 180;

export interface HostSpec {
  id: string;
  hostname: string;
  os: OsInfo;
  kernel: string;
  arch: string;
  agentVersion: string;
  /** How long before the anchor the host enrolled. */
  enrolledAgo: number;
  intervalMs: number;
  /** The host stopped reporting this long before the anchor (it will show as STALE). */
  silentFor?: number;
  /** Status of each check before any change; unlisted checks pass. */
  baseline?: Partial<Record<string, CheckStatus>>;
  /** From `ago` before the anchor onwards, the check has `status`. */
  changes?: readonly { checkId: string; status: CheckStatus; ago: number }[];
  /** A check that alternates between two statuses every `periodMs`. */
  flap?: {
    checkId: string;
    periodMs: number;
    statuses: readonly [CheckStatus, CheckStatus];
    sinceAgo: number;
  };
}

export class MockApiError extends Error {
  readonly status: number;
  readonly title: string;
  readonly errors: { field: string; message: string }[] | undefined;

  constructor(
    status: number,
    title: string,
    detail: string,
    errors?: { field: string; message: string }[],
  ) {
    super(detail);
    this.status = status;
    this.title = title;
    this.errors = errors;
  }
}

function notFound(detail: string): MockApiError {
  return new MockApiError(404, 'Not Found', detail);
}

function badRequest(field: string, message: string): MockApiError {
  return new MockApiError(400, 'Bad Request', 'Request parameters failed validation.', [
    { field, message },
  ]);
}

function hex(value: number, width: number): string {
  return value.toString(16).padStart(width, '0');
}

/** UUID-shaped, deterministic scan IDs that encode (host, scan index). */
function scanIdFor(hostIndex: number, index: number): string {
  return `${hex(hostIndex + 1, 8)}-5ca0-4000-8000-${hex(index, 12)}`;
}

function parseScanId(scanId: string): { hostIndex: number; index: number } | undefined {
  const match = /^([0-9a-f]{8})-5ca0-4000-8000-([0-9a-f]{12})$/.exec(scanId);
  if (!match?.[1] || !match[2]) return undefined;
  return { hostIndex: Number.parseInt(match[1], 16) - 1, index: Number.parseInt(match[2], 16) };
}

function eventIdFor(hostIndex: number, checkIndex: number, index: number): string {
  return `${hex(hostIndex + 1, 8)}-${hex(checkIndex, 4)}-4000-9000-${hex(index, 12)}`;
}

const iso = (ms: number) => new Date(ms).toISOString();

const roundOne = (value: number) => Math.round(value * 10) / 10;

/** The contract's score: 100 × Σw(PASS) / Σw(PASS ∪ FAIL ∪ ERROR), one decimal. */
export function computeScore(
  statuses: readonly { status: CheckStatus; severity: MockCheck['severity'] }[],
): number | null {
  let passed = 0;
  let applicable = 0;
  for (const { status, severity } of statuses) {
    if (status === 'NOT_APPLICABLE') continue;
    applicable += WEIGHTS[severity];
    if (status === 'PASS') passed += WEIGHTS[severity];
  }
  return applicable === 0 ? null : roundOne((100 * passed) / applicable);
}

/** The contract's drift direction for a status change; `null` when nothing changed. */
export function driftDirection(from: CheckStatus, to: CheckStatus): DriftDirection | null {
  if (from === to) return null;
  const wasOk = from === 'PASS' || from === 'NOT_APPLICABLE';
  const nowFailing = to === 'FAIL' || to === 'ERROR';
  if (wasOk && nowFailing) return 'REGRESSED';
  if ((from === 'FAIL' || from === 'ERROR') && to === 'PASS') return 'RESOLVED';
  return 'CHANGED';
}

const isFailingStatus = (status: CheckStatus) => status === 'FAIL' || status === 'ERROR';

const DIRECTION_RANK: Record<DriftDirection, number> = { REGRESSED: 0, RESOLVED: 1, CHANGED: 2 };

function resultFor(check: MockCheck, status: CheckStatus): CheckResult {
  const base = {
    checkId: check.id,
    title: check.title,
    category: check.category,
    severity: check.severity,
    status,
    expected: check.expected,
  };
  switch (status) {
    case 'PASS':
      return { ...base, actual: check.pass[0], evidence: check.pass[1] };
    case 'FAIL':
      return { ...base, actual: check.fail[0], evidence: check.fail[1] };
    case 'ERROR':
      return { ...base, actual: null, evidence: check.error ?? 'Check could not be evaluated' };
    case 'NOT_APPLICABLE':
      return { ...base, actual: null, evidence: check.notApplicable ?? 'Not present on host' };
  }
}

/** Results ordered by category, then check ID, as the contract specifies. */
const ORDERED_CHECKS = [...CATALOG].sort(
  (a, b) => a.category.localeCompare(b.category) || a.id.localeCompare(b.id),
);

class MockHost {
  readonly spec: HostSpec;
  readonly index: number;
  readonly firstScanAt: number;
  readonly enrolledAt: number;
  private readonly reportUntil: number;
  private readonly changes: { checkId: string; status: CheckStatus; at: number }[];
  private readonly flapStart: number;

  constructor(spec: HostSpec, index: number, anchor: number) {
    this.spec = spec;
    this.index = index;
    this.enrolledAt = anchor - spec.enrolledAgo;
    // Stagger hosts so they do not all report on the same second.
    this.firstScanAt = this.enrolledAt + 5 * SECOND + index * 7 * SECOND;
    this.reportUntil = spec.silentFor === undefined ? Infinity : anchor - spec.silentFor;
    this.changes = (spec.changes ?? [])
      .map((change) => ({ ...change, at: anchor - change.ago }))
      .sort((a, b) => a.at - b.at);
    this.flapStart = spec.flap ? anchor - spec.flap.sinceAgo : Infinity;
  }

  /** Index of the newest scan finished at or before `time`, or -1 if none. */
  latestIndexAt(time: number): number {
    const until = Math.min(time, this.reportUntil);
    if (until < this.firstScanAt) return -1;
    return Math.floor((until - this.firstScanAt) / this.spec.intervalMs);
  }

  finishedAt(index: number): number {
    return this.firstScanAt + index * this.spec.intervalMs;
  }

  statusAt(checkId: string, time: number): CheckStatus {
    const { flap } = this.spec;
    if (flap?.checkId === checkId && time >= this.flapStart) {
      const phase = Math.floor((time - this.flapStart) / flap.periodMs) % 2;
      return flap.statuses[phase === 0 ? 0 : 1];
    }
    let status = this.spec.baseline?.[checkId] ?? 'PASS';
    for (const change of this.changes) {
      if (change.checkId === checkId && change.at <= time) status = change.status;
    }
    return status;
  }

  results(index: number): CheckResult[] {
    const time = this.finishedAt(index);
    return ORDERED_CHECKS.map((check) => resultFor(check, this.statusAt(check.id, time)));
  }

  summary(index: number): ScanSummary {
    const finishedAt = this.finishedAt(index);
    const results = this.results(index);
    const counts: StatusCounts = { pass: 0, fail: 0, error: 0, notApplicable: 0 };
    for (const { status } of results) {
      if (status === 'PASS') counts.pass += 1;
      else if (status === 'FAIL') counts.fail += 1;
      else if (status === 'ERROR') counts.error += 1;
      else counts.notApplicable += 1;
    }
    return {
      id: scanIdFor(this.index, index),
      hostId: this.spec.id,
      agentVersion: this.spec.agentVersion,
      startedAt: iso(finishedAt - SCAN_DURATION_MS),
      finishedAt: iso(finishedAt),
      receivedAt: iso(finishedAt + RECEIVE_DELAY_MS),
      score: computeScore(results),
      counts,
    };
  }

  toHost(now: number): Host {
    const latest = this.latestIndexAt(now);
    const latestScan = latest >= 0 ? this.summary(latest) : null;
    const lastSeenAt = latestScan ? Date.parse(latestScan.receivedAt) : this.enrolledAt;
    return {
      id: this.spec.id,
      hostname: this.spec.hostname,
      os: this.spec.os,
      kernel: this.spec.kernel,
      arch: this.spec.arch,
      agentVersion: this.spec.agentVersion,
      enrolledAt: iso(this.enrolledAt),
      lastSeenAt: iso(lastSeenAt),
      status: now - lastSeenAt > STALE_AFTER_MS ? 'STALE' : 'ONLINE',
      latestScan,
    };
  }

  /**
   * Moments at which a check's status may have changed, newest first. A flapping check
   * flips every period; only the most recent flips can make the cut, and a direction
   * filter keeps at most every other one, so `2 * limit` flips is always enough.
   */
  private transitionTimes(upTo: number, limit: number): { checkId: string; at: number }[] {
    const candidates = this.changes
      .filter((change) => change.at <= upTo)
      .map(({ checkId, at }) => ({ checkId, at }));
    const { flap } = this.spec;
    if (flap && this.flapStart <= upTo) {
      const lastFlip = Math.floor((upTo - this.flapStart) / flap.periodMs);
      for (let flip = lastFlip; flip >= Math.max(0, lastFlip - 2 * limit); flip -= 1) {
        candidates.push({ checkId: flap.checkId, at: this.flapStart + flip * flap.periodMs });
      }
    }
    return candidates.sort((a, b) => b.at - a.at);
  }

  /** Drift events this host has produced up to `now`, newest first. */
  events(now: number, limit: number, direction: DriftDirection | undefined): DriftEvent[] {
    const latest = this.latestIndexAt(now);
    if (latest < 1) return [];
    const found: DriftEvent[] = [];
    const seen = new Set<string>();
    for (const { checkId, at } of this.transitionTimes(this.finishedAt(latest), limit)) {
      if (found.length >= limit) break;
      const index = Math.max(1, Math.ceil((at - this.firstScanAt) / this.spec.intervalMs));
      if (index > latest || seen.has(`${checkId}@${index}`)) continue;
      seen.add(`${checkId}@${index}`);
      const before = this.statusAt(checkId, this.finishedAt(index - 1));
      const after = this.statusAt(checkId, this.finishedAt(index));
      const eventDirection = driftDirection(before, after);
      if (!eventDirection || (direction && eventDirection !== direction)) continue;
      const checkIndex = CATALOG.findIndex((check) => check.id === checkId);
      const check = CATALOG[checkIndex];
      if (!check) continue;
      found.push({
        ...changeFor(check, before, after, eventDirection),
        id: eventIdFor(this.index, checkIndex, index),
        hostId: this.spec.id,
        hostname: this.spec.hostname,
        scanId: scanIdFor(this.index, index),
        detectedAt: iso(this.finishedAt(index)),
      });
    }
    return found;
  }
}

function changeFor(
  check: MockCheck,
  from: CheckStatus,
  to: CheckStatus,
  direction: DriftDirection,
): DriftChange {
  return {
    checkId: check.id,
    title: check.title,
    category: check.category,
    severity: check.severity,
    fromStatus: from,
    toStatus: to,
    fromEvidence: resultFor(check, from).evidence ?? null,
    toEvidence: resultFor(check, to).evidence ?? null,
    direction,
  };
}

export interface MockFleetOptions {
  hosts: readonly HostSpec[];
  /** Fixed moment the posture timelines are relative to (keeps scan IDs stable). */
  anchor: number;
  /** Current time; defaults to the wall clock so the fleet keeps scanning. */
  now?: () => number;
}

export class MockFleet {
  private hosts: MockHost[];
  private readonly now: () => number;

  constructor({ hosts, anchor, now = () => Date.now() }: MockFleetOptions) {
    this.hosts = hosts.map((spec, index) => new MockHost(spec, index, anchor));
    this.now = now;
  }

  private host(hostId: string): MockHost {
    const host = this.hosts.find((candidate) => candidate.spec.id === hostId);
    if (!host) throw notFound(`Host ${hostId} does not exist.`);
    return host;
  }

  private latestResults(now: number) {
    return this.hosts.flatMap((host) => {
      const latest = host.latestIndexAt(now);
      return latest >= 0 ? [{ host, index: latest, results: host.results(latest) }] : [];
    });
  }

  summary(): FleetSummary {
    const now = this.now();
    const hosts = this.hosts.map((host) => host.toHost(now));
    const scores = hosts.flatMap((host) =>
      host.latestScan?.score === null || host.latestScan === null ? [] : [host.latestScan.score],
    );
    const latest = this.latestResults(now);
    const lastScan = Math.max(
      ...latest.map(({ host, index }) => host.finishedAt(index)),
      Number.NEGATIVE_INFINITY,
    );
    return {
      hostCount: hosts.length,
      onlineCount: hosts.filter((host) => host.status === 'ONLINE').length,
      staleCount: hosts.filter((host) => host.status === 'STALE').length,
      averageScore:
        scores.length === 0 ? null : roundOne(scores.reduce((a, b) => a + b, 0) / scores.length),
      hostsWithCriticalFailures: latest.filter(({ results }) =>
        results.some((result) => result.severity === 'critical' && isFailingStatus(result.status)),
      ).length,
      topFailingChecks: this.checks()
        .map((check) => ({
          checkId: check.id,
          title: check.title,
          category: check.category,
          severity: check.severity,
          failingHosts: check.counts.fail + check.counts.error,
        }))
        .filter((check) => check.failingHosts > 0)
        .slice(0, 5),
      lastScanAt: Number.isFinite(lastScan) ? iso(lastScan) : null,
    };
  }

  trend(hours: number, bucketMinutes: number): FleetTrend {
    if (!Number.isInteger(hours) || hours < 1 || hours > 720) {
      throw badRequest('hours', 'must be an integer between 1 and 720');
    }
    if (!Number.isInteger(bucketMinutes) || bucketMinutes < 1 || bucketMinutes > 1440) {
      throw badRequest('bucketMinutes', 'must be an integer between 1 and 1440');
    }
    const count = Math.ceil((hours * 60) / bucketMinutes);
    if (count > MAX_TREND_POINTS) {
      throw badRequest('bucketMinutes', `would produce ${count} points; the maximum is 500`);
    }
    const now = this.now();
    const bucketMs = bucketMinutes * MINUTE;
    const lastStart = Math.floor(now / bucketMs) * bucketMs;
    const points = Array.from({ length: count }, (_, position) => {
      const start = lastStart - (count - 1 - position) * bucketMs;
      const end = Math.min(start + bucketMs, now);
      const scores = this.hosts.flatMap((host) => {
        const index = host.latestIndexAt(end);
        const score = index >= 0 ? host.summary(index).score : null;
        return score === null ? [] : [score];
      });
      return {
        bucketStart: iso(start),
        averageScore:
          scores.length === 0 ? null : roundOne(scores.reduce((a, b) => a + b, 0) / scores.length),
        hostCount: scores.length,
      };
    });
    return { bucketMinutes, points };
  }

  listHosts(query: HostListQuery): HostPage {
    const now = this.now();
    const page = query.page ?? 0;
    const size = query.size ?? 50;
    if (page < 0) throw badRequest('page', 'must be 0 or greater');
    if (size < 1 || size > 200) throw badRequest('size', 'must be between 1 and 200');
    const needle = query.query?.toLowerCase() ?? '';
    const direction = query.direction === 'desc' ? -1 : 1;
    const sort = query.sort ?? 'hostname';
    const hosts = this.hosts
      .map((host) => host.toHost(now))
      .filter((host) => host.hostname.toLowerCase().includes(needle))
      .filter((host) => !query.status || host.status === query.status)
      .sort((a, b) => {
        if (sort === 'score') {
          const left = a.latestScan?.score ?? null;
          const right = b.latestScan?.score ?? null;
          // Hosts without a score sort last in either direction.
          if (left === null || right === null)
            return (left === null ? 1 : 0) - (right === null ? 1 : 0);
          return (left - right) * direction || a.hostname.localeCompare(b.hostname);
        }
        if (sort === 'lastSeenAt') {
          return (Date.parse(a.lastSeenAt) - Date.parse(b.lastSeenAt)) * direction;
        }
        return a.hostname.localeCompare(b.hostname) * direction;
      });
    return {
      items: hosts.slice(page * size, page * size + size),
      page,
      size,
      totalItems: hosts.length,
    };
  }

  /** Removes the host; its scans, results and events go with it (they are derived). */
  decommission(hostId: string): void {
    const host = this.host(hostId);
    this.hosts = this.hosts.filter((candidate) => candidate !== host);
  }

  getHost(hostId: string): Host {
    return this.host(hostId).toHost(this.now());
  }

  hostScans(hostId: string, limit = 100): ScanSummary[] {
    if (limit < 1 || limit > 500) throw badRequest('limit', 'must be between 1 and 500');
    const host = this.host(hostId);
    const latest = host.latestIndexAt(this.now());
    const scans: ScanSummary[] = [];
    for (let index = latest; index >= 0 && scans.length < limit; index -= 1) {
      scans.push(host.summary(index));
    }
    return scans;
  }

  private locateScan(scanId: string): { host: MockHost; index: number } {
    const parsed = parseScanId(scanId);
    const host = parsed ? this.hosts[parsed.hostIndex] : undefined;
    if (!parsed || !host || parsed.index > host.latestIndexAt(this.now())) {
      throw notFound(`Scan ${scanId} does not exist.`);
    }
    return { host, index: parsed.index };
  }

  getScan(scanId: string): ScanDetail {
    const { host, index } = this.locateScan(scanId);
    return { ...host.summary(index), hostname: host.spec.hostname, results: host.results(index) };
  }

  drift(hostId: string, range: { from?: string; to?: string }): DriftReport {
    const host = this.host(hostId);
    const resolve = (scanId: string, field: string) => {
      const located = this.locateScan(scanId);
      if (located.host !== host) throw badRequest(field, 'scan belongs to a different host');
      return located.index;
    };
    const latest = host.latestIndexAt(this.now());
    const toIndex = range.to ? resolve(range.to, 'to') : latest;
    if (toIndex < 0) return { hostId, fromScan: null, toScan: null, changes: [] };
    const fromIndex = range.from ? resolve(range.from, 'from') : toIndex - 1;
    if (fromIndex < 0) {
      return { hostId, fromScan: null, toScan: host.summary(toIndex), changes: [] };
    }
    const before = host.results(fromIndex);
    const after = host.results(toIndex);
    const changes = after.flatMap((result) => {
      const previous = before.find((candidate) => candidate.checkId === result.checkId);
      const direction = previous ? driftDirection(previous.status, result.status) : null;
      const check = CATALOG.find((candidate) => candidate.id === result.checkId);
      return previous && direction && check
        ? [changeFor(check, previous.status, result.status, direction)]
        : [];
    });
    changes.sort(
      (a, b) =>
        DIRECTION_RANK[a.direction] - DIRECTION_RANK[b.direction] ||
        WEIGHTS[b.severity] - WEIGHTS[a.severity] ||
        a.checkId.localeCompare(b.checkId),
    );
    return {
      hostId,
      fromScan: host.summary(fromIndex),
      toScan: host.summary(toIndex),
      changes,
    };
  }

  checks(): CheckSummary[] {
    const latest = this.latestResults(this.now());
    return CATALOG.map((check) => {
      const counts: StatusCounts = { pass: 0, fail: 0, error: 0, notApplicable: 0 };
      for (const { results } of latest) {
        const status = results.find((result) => result.checkId === check.id)?.status;
        if (status === 'PASS') counts.pass += 1;
        else if (status === 'FAIL') counts.fail += 1;
        else if (status === 'ERROR') counts.error += 1;
        else if (status === 'NOT_APPLICABLE') counts.notApplicable += 1;
      }
      return {
        id: check.id,
        title: check.title,
        category: check.category,
        severity: check.severity,
        counts,
      };
    }).sort(
      (a, b) =>
        b.counts.fail + b.counts.error - (a.counts.fail + a.counts.error) ||
        WEIGHTS[b.severity] - WEIGHTS[a.severity] ||
        a.id.localeCompare(b.id),
    );
  }

  getCheck(checkId: string): CheckDetail {
    const summary = this.checks().find((check) => check.id === checkId);
    if (!summary) throw notFound(`Check ${checkId} is not in the catalog.`);
    const hosts: CheckHostStatus[] = this.latestResults(this.now()).flatMap(
      ({ host, index, results }) => {
        const result = results.find((candidate) => candidate.checkId === checkId);
        return result
          ? [
              {
                hostId: host.spec.id,
                hostname: host.spec.hostname,
                status: result.status,
                expected: result.expected ?? null,
                actual: result.actual ?? null,
                evidence: result.evidence ?? null,
                scanId: scanIdFor(host.index, index),
                finishedAt: iso(host.finishedAt(index)),
              },
            ]
          : [];
      },
    );
    hosts.sort(
      (a, b) =>
        Number(isFailingStatus(b.status)) - Number(isFailingStatus(a.status)) ||
        a.hostname.localeCompare(b.hostname),
    );
    return { ...summary, hosts };
  }

  events(query: { hostId?: string; direction?: DriftDirection; limit?: number }): DriftEvent[] {
    const limit = query.limit ?? 50;
    if (limit < 1 || limit > 200) throw badRequest('limit', 'must be between 1 and 200');
    const now = this.now();
    const hosts = query.hostId ? [this.host(query.hostId)] : this.hosts;
    return hosts
      .flatMap((host) => host.events(now, limit, query.direction))
      .sort(
        (a, b) =>
          Date.parse(b.detectedAt) - Date.parse(a.detectedAt) ||
          a.hostname.localeCompare(b.hostname),
      )
      .slice(0, limit);
  }
}

const WSL_KERNEL = '6.6.87.2-microsoft-standard-WSL2';

/** The demo fleet from docs/CONTRACTS.md, plus one host that stopped reporting. */
export const DEMO_HOSTS: readonly HostSpec[] = [
  {
    id: '3f6c1a52-8d0e-4b7a-9c21-5e8f0d4a7b13',
    hostname: 'host-ubuntu-hardened',
    os: { id: 'ubuntu', version: '24.04', prettyName: 'Ubuntu 24.04.3 LTS' },
    kernel: WSL_KERNEL,
    arch: 'amd64',
    agentVersion: '0.1.0',
    enrolledAgo: 30 * DAY,
    intervalMs: 30 * SECOND,
    baseline: { 'ssh.login_grace_time': 'FAIL', 'ssh.idle_timeout': 'FAIL' },
    changes: [{ checkId: 'ssh.idle_timeout', status: 'PASS', ago: 6 * DAY }],
  },
  {
    id: 'a91d4e07-2b6c-4f38-8e5a-c7b30f19d264',
    hostname: 'host-debian-legacy',
    os: { id: 'debian', version: '12', prettyName: 'Debian GNU/Linux 12 (bookworm)' },
    kernel: WSL_KERNEL,
    arch: 'amd64',
    agentVersion: '0.1.0',
    enrolledAgo: 21 * DAY,
    intervalMs: 30 * SECOND,
    baseline: {
      'ssh.permit_root_login': 'FAIL',
      'ssh.password_authentication': 'FAIL',
      'ssh.max_auth_tries': 'FAIL',
      'ssh.x11_forwarding': 'FAIL',
      'ssh.login_grace_time': 'FAIL',
      'ssh.idle_timeout': 'FAIL',
      'accounts.uid0_only_root': 'FAIL',
      'accounts.no_empty_passwords': 'FAIL',
      'accounts.pass_max_days': 'FAIL',
      'accounts.pass_min_days': 'FAIL',
    },
    changes: [
      { checkId: 'accounts.no_empty_passwords', status: 'PASS', ago: 3 * DAY },
      { checkId: 'ssh.max_auth_tries', status: 'ERROR', ago: 25 * MINUTE },
    ],
  },
  {
    id: '5c27b8e4-6f13-4d90-a2b8-81e4c6d05f79',
    hostname: 'host-rocky-web',
    os: { id: 'rocky', version: '9.6', prettyName: 'Rocky Linux 9.6 (Blue Onyx)' },
    kernel: WSL_KERNEL,
    arch: 'amd64',
    agentVersion: '0.1.0',
    enrolledAgo: 12 * DAY,
    intervalMs: 30 * SECOND,
    baseline: {
      'ssh.password_authentication': 'FAIL',
      'ssh.x11_forwarding': 'FAIL',
      'network.icmp_redirects_rejected': 'FAIL',
      'filesystem.crontab_permissions': 'NOT_APPLICABLE',
    },
    changes: [
      { checkId: 'network.ip_forwarding_disabled', status: 'FAIL', ago: 26 * HOUR },
      { checkId: 'accounts.pass_max_days', status: 'ERROR', ago: 2 * HOUR },
      { checkId: 'ssh.x11_forwarding', status: 'PASS', ago: 40 * MINUTE },
    ],
  },
  {
    id: 'd84a0f3b-17c5-4e62-b9d1-0a6e2c5f8b47',
    hostname: 'host-alpine-drifting',
    os: { id: 'alpine', version: '3.22.2', prettyName: 'Alpine Linux v3.22' },
    kernel: WSL_KERNEL,
    arch: 'amd64',
    agentVersion: '0.1.0',
    enrolledAgo: 3 * DAY,
    intervalMs: 30 * SECOND,
    baseline: { 'filesystem.crontab_permissions': 'NOT_APPLICABLE' },
    flap: {
      checkId: 'ssh.permit_root_login',
      periodMs: 4 * MINUTE,
      statuses: ['PASS', 'FAIL'],
      sinceAgo: 3 * DAY,
    },
  },
  {
    id: '7e3b9c60-4a28-4f15-8d7e-2c9a1b6f0e35',
    hostname: 'host-fedora-batch',
    os: { id: 'fedora', version: '42', prettyName: 'Fedora Linux 42 (Container Image)' },
    kernel: WSL_KERNEL,
    arch: 'arm64',
    agentVersion: '0.1.0',
    enrolledAgo: 9 * DAY,
    intervalMs: 5 * MINUTE,
    silentFor: 3 * HOUR,
    baseline: {
      ...Object.fromEntries(
        CATALOG.filter((check) => check.category === 'ssh').map((check) => [
          check.id,
          'NOT_APPLICABLE' as const,
        ]),
      ),
      'accounts.pass_max_days': 'FAIL',
      'network.syn_cookies_enabled': 'FAIL',
    },
  },
];

export function createDemoFleet(options: { anchor?: number; now?: () => number } = {}): MockFleet {
  return new MockFleet({
    hosts: DEMO_HOSTS,
    anchor: options.anchor ?? Date.now(),
    now: options.now,
  });
}
