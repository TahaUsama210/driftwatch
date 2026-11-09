import type {
  CheckDetail,
  CheckHostStatus,
  CheckResult,
  DriftChange,
  DriftEvent,
  DriftReport,
  FleetSummary,
  Host,
  HostPage,
  ScanDetail,
  ScanSummary,
} from '../api/types';

/**
 * Small builders for contract-shaped fixtures. Each returns a valid object with
 * sensible defaults; tests override only the fields they care about.
 */

export const HOST_ID = '3f6c1a52-8d0e-4b7a-9c21-5e8f0d4a7b13';

export function buildScanSummary(overrides: Partial<ScanSummary> = {}): ScanSummary {
  return {
    id: '00000001-5ca0-4000-8000-000000000001',
    hostId: HOST_ID,
    agentVersion: '0.1.0',
    startedAt: '2026-11-03T13:59:58Z',
    finishedAt: '2026-11-03T14:00:00Z',
    receivedAt: '2026-11-03T14:00:00Z',
    score: 92.5,
    counts: { pass: 18, fail: 2, error: 0, notApplicable: 1 },
    ...overrides,
  };
}

export function buildHost(overrides: Partial<Host> = {}): Host {
  return {
    id: HOST_ID,
    hostname: 'host-ubuntu-hardened',
    os: { id: 'ubuntu', version: '24.04', prettyName: 'Ubuntu 24.04.3 LTS' },
    kernel: '6.6.87.2-microsoft-standard-WSL2',
    arch: 'amd64',
    agentVersion: '0.1.0',
    enrolledAt: '2026-10-04T14:00:00Z',
    lastSeenAt: '2026-11-03T14:00:00Z',
    status: 'ONLINE',
    latestScan: buildScanSummary(),
    ...overrides,
  };
}

export function buildHostPage(items: Host[], overrides: Partial<HostPage> = {}): HostPage {
  return { items, page: 0, size: 25, totalItems: items.length, ...overrides };
}

export function buildCheckResult(overrides: Partial<CheckResult> = {}): CheckResult {
  return {
    checkId: 'ssh.permit_root_login',
    title: 'SSH root login is disabled',
    category: 'ssh',
    severity: 'high',
    status: 'PASS',
    expected: 'no',
    actual: 'no',
    evidence: '/etc/ssh/sshd_config:32: PermitRootLogin no',
    ...overrides,
  };
}

export function buildScanDetail(overrides: Partial<ScanDetail> = {}): ScanDetail {
  return {
    ...buildScanSummary(),
    hostname: 'host-ubuntu-hardened',
    results: [buildCheckResult()],
    ...overrides,
  };
}

export function buildDriftChange(overrides: Partial<DriftChange> = {}): DriftChange {
  return {
    checkId: 'ssh.permit_root_login',
    title: 'SSH root login is disabled',
    category: 'ssh',
    severity: 'high',
    fromStatus: 'PASS',
    toStatus: 'FAIL',
    fromEvidence: '/etc/ssh/sshd_config:32: PermitRootLogin no',
    toEvidence: '/etc/ssh/sshd_config:32: PermitRootLogin yes',
    direction: 'REGRESSED',
    ...overrides,
  };
}

export function buildDriftReport(overrides: Partial<DriftReport> = {}): DriftReport {
  return {
    hostId: HOST_ID,
    fromScan: buildScanSummary({ id: '00000001-5ca0-4000-8000-000000000000' }),
    toScan: buildScanSummary(),
    changes: [buildDriftChange()],
    ...overrides,
  };
}

export function buildDriftEvent(overrides: Partial<DriftEvent> = {}): DriftEvent {
  return {
    ...buildDriftChange(),
    id: '00000001-0000-4000-9000-000000000001',
    hostId: HOST_ID,
    hostname: 'host-ubuntu-hardened',
    scanId: '00000001-5ca0-4000-8000-000000000001',
    detectedAt: '2026-11-03T14:00:00Z',
    ...overrides,
  };
}

export function buildFleetSummary(overrides: Partial<FleetSummary> = {}): FleetSummary {
  return {
    hostCount: 4,
    onlineCount: 4,
    staleCount: 0,
    averageScore: 91.2,
    hostsWithCriticalFailures: 0,
    topFailingChecks: [],
    lastScanAt: '2026-11-03T14:00:00Z',
    ...overrides,
  };
}

export function buildCheckHostStatus(overrides: Partial<CheckHostStatus> = {}): CheckHostStatus {
  return {
    hostId: HOST_ID,
    hostname: 'host-ubuntu-hardened',
    status: 'PASS',
    expected: 'no',
    actual: 'no',
    evidence: '/etc/ssh/sshd_config:32: PermitRootLogin no',
    scanId: '00000001-5ca0-4000-8000-000000000001',
    finishedAt: '2026-11-03T14:00:00Z',
    ...overrides,
  };
}

export function buildCheckDetail(overrides: Partial<CheckDetail> = {}): CheckDetail {
  return {
    id: 'ssh.permit_root_login',
    title: 'SSH root login is disabled',
    category: 'ssh',
    severity: 'high',
    counts: { pass: 1, fail: 0, error: 0, notApplicable: 0 },
    hosts: [buildCheckHostStatus()],
    ...overrides,
  };
}
