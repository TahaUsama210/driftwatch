import type { Category, Severity } from '../api/types';

/**
 * The phase-1 check catalog from docs/CONTRACTS.md (IDs, titles, categories and
 * severities are fixed there), plus realistic expected/actual/evidence strings so the
 * mock API's results read like real agent output.
 */
export interface MockCheck {
  id: string;
  title: string;
  category: Category;
  severity: Severity;
  expected: string;
  /** [actual, evidence] when the check passes. */
  pass: readonly [string, string];
  /** [actual, evidence] when it fails. */
  fail: readonly [string, string];
  /** Evidence when the check could not be evaluated. */
  error?: string;
  /**
   * Evidence when the checked thing does not exist on the host. Only the checks the
   * contract allows to be NOT_APPLICABLE have one: every `ssh.*` check (no sshd_config)
   * and `filesystem.crontab_permissions` (no /etc/crontab). A missing login.defs is a FAIL.
   */
  notApplicable?: string;
}

const SSHD = '/etc/ssh/sshd_config';
const NO_SSHD = `${SSHD} not found; SSH server is not installed`;

export const CATALOG: readonly MockCheck[] = [
  {
    id: 'ssh.permit_root_login',
    title: 'SSH root login is disabled',
    category: 'ssh',
    severity: 'high',
    expected: 'no',
    pass: ['no', `${SSHD}:32: PermitRootLogin no`],
    fail: ['yes', `${SSHD}:32: PermitRootLogin yes`],
    notApplicable: NO_SSHD,
  },
  {
    id: 'ssh.password_authentication',
    title: 'SSH password authentication is disabled',
    category: 'ssh',
    severity: 'high',
    expected: 'no',
    pass: ['no', `${SSHD}:58: PasswordAuthentication no`],
    fail: ['yes', 'PasswordAuthentication is not set; the OpenSSH default is yes'],
    notApplicable: NO_SSHD,
  },
  {
    id: 'ssh.permit_empty_passwords',
    title: 'SSH rejects empty passwords',
    category: 'ssh',
    severity: 'critical',
    expected: 'no',
    pass: ['no', 'PermitEmptyPasswords is not set; the OpenSSH default is no'],
    fail: ['yes', `${SSHD}:61: PermitEmptyPasswords yes`],
    notApplicable: NO_SSHD,
  },
  {
    id: 'ssh.max_auth_tries',
    title: 'SSH limits authentication attempts',
    category: 'ssh',
    severity: 'medium',
    expected: '<= 4',
    pass: ['3', `${SSHD}:40: MaxAuthTries 3`],
    fail: ['6', 'MaxAuthTries is not set; the OpenSSH default is 6'],
    error: `${SSHD}:40: MaxAuthTries value "three" is not an integer`,
    notApplicable: NO_SSHD,
  },
  {
    id: 'ssh.x11_forwarding',
    title: 'SSH X11 forwarding is disabled',
    category: 'ssh',
    severity: 'low',
    expected: 'no',
    pass: ['no', 'X11Forwarding is not set; the OpenSSH default is no'],
    fail: ['yes', `${SSHD}:89: X11Forwarding yes`],
    notApplicable: NO_SSHD,
  },
  {
    id: 'ssh.login_grace_time',
    title: 'SSH login grace time is 60s or less',
    category: 'ssh',
    severity: 'low',
    expected: '1-60s',
    pass: ['30s', `${SSHD}:37: LoginGraceTime 30`],
    fail: ['120s', 'LoginGraceTime is not set; the OpenSSH default is 120'],
    notApplicable: NO_SSHD,
  },
  {
    id: 'ssh.idle_timeout',
    title: 'SSH idle sessions time out',
    category: 'ssh',
    severity: 'low',
    expected: '1-900s',
    pass: ['300s', `${SSHD}:104: ClientAliveInterval 300`],
    fail: ['0', 'ClientAliveInterval is not set; the OpenSSH default is 0 (disabled)'],
    notApplicable: NO_SSHD,
  },
  {
    id: 'ssh.config_permissions',
    title: 'sshd_config is owned by root and not group/world accessible',
    category: 'ssh',
    severity: 'medium',
    expected: 'uid 0, mode <= 0600',
    pass: ['uid 0, mode 0600', `${SSHD}: owner=0 mode=0600`],
    fail: ['uid 0, mode 0644', `${SSHD}: owner=0 mode=0644`],
    notApplicable: NO_SSHD,
  },
  {
    id: 'accounts.shadow_permissions',
    title: '/etc/shadow is protected',
    category: 'accounts',
    severity: 'high',
    expected: 'uid 0, mode <= 0640',
    pass: ['uid 0, mode 0640', '/etc/shadow: owner=0 group=42 mode=0640'],
    fail: ['uid 0, mode 0644', '/etc/shadow: owner=0 group=0 mode=0644'],
  },
  {
    id: 'accounts.passwd_permissions',
    title: '/etc/passwd is not writable by group or others',
    category: 'accounts',
    severity: 'medium',
    expected: 'uid 0, mode <= 0644',
    pass: ['uid 0, mode 0644', '/etc/passwd: owner=0 mode=0644'],
    fail: ['uid 0, mode 0664', '/etc/passwd: owner=0 mode=0664'],
  },
  {
    id: 'accounts.uid0_only_root',
    title: 'Only root has UID 0',
    category: 'accounts',
    severity: 'critical',
    expected: 'root',
    pass: ['root', '/etc/passwd: one entry with UID 0 (root)'],
    fail: ['root, toor', '/etc/passwd:24: toor:x:0:0::/root:/bin/sh'],
  },
  {
    id: 'accounts.no_empty_passwords',
    title: 'No account has an empty password',
    category: 'accounts',
    severity: 'critical',
    expected: '0 accounts',
    pass: ['0 accounts', '/etc/shadow: no entry has an empty password field'],
    fail: ['1 account', '/etc/shadow:19: deploy has an empty password field'],
  },
  {
    id: 'accounts.pass_max_days',
    title: 'Passwords expire within 365 days',
    category: 'accounts',
    severity: 'medium',
    expected: '1-365',
    pass: ['90', '/etc/login.defs:165: PASS_MAX_DAYS 90'],
    fail: ['99999', '/etc/login.defs:165: PASS_MAX_DAYS 99999'],
    error: '/etc/login.defs:165: PASS_MAX_DAYS value "ninety" is not an integer',
  },
  {
    id: 'accounts.pass_min_days',
    title: 'Passwords have a minimum age',
    category: 'accounts',
    severity: 'low',
    expected: '>= 1',
    pass: ['1', '/etc/login.defs:166: PASS_MIN_DAYS 1'],
    fail: ['0', '/etc/login.defs:166: PASS_MIN_DAYS 0'],
  },
  {
    id: 'accounts.pass_warn_age',
    title: 'Users are warned before password expiry',
    category: 'accounts',
    severity: 'low',
    expected: '>= 7',
    pass: ['7', '/etc/login.defs:167: PASS_WARN_AGE 7'],
    fail: ['3', '/etc/login.defs:167: PASS_WARN_AGE 3'],
  },
  {
    id: 'network.ip_forwarding_disabled',
    title: 'IPv4 forwarding is disabled',
    category: 'network',
    severity: 'medium',
    expected: '0',
    pass: ['0', 'net.ipv4.ip_forward = 0'],
    fail: ['1', 'net.ipv4.ip_forward = 1'],
  },
  {
    id: 'network.icmp_redirects_rejected',
    title: 'ICMP redirects are not accepted',
    category: 'network',
    severity: 'medium',
    expected: '0',
    pass: ['0', 'net.ipv4.conf.all.accept_redirects = 0'],
    fail: ['1', 'net.ipv4.conf.all.accept_redirects = 1'],
  },
  {
    id: 'network.source_routing_rejected',
    title: 'Source-routed packets are not accepted',
    category: 'network',
    severity: 'medium',
    expected: '0',
    pass: ['0', 'net.ipv4.conf.all.accept_source_route = 0'],
    fail: ['1', 'net.ipv4.conf.all.accept_source_route = 1'],
  },
  {
    id: 'network.syn_cookies_enabled',
    title: 'TCP SYN cookies are enabled',
    category: 'network',
    severity: 'medium',
    expected: '1',
    pass: ['1', 'net.ipv4.tcp_syncookies = 1'],
    fail: ['0', 'net.ipv4.tcp_syncookies = 0'],
  },
  {
    id: 'kernel.aslr_enabled',
    title: 'Full address space layout randomization is enabled',
    category: 'kernel',
    severity: 'high',
    expected: '2',
    pass: ['2', 'kernel.randomize_va_space = 2'],
    fail: ['0', 'kernel.randomize_va_space = 0'],
  },
  {
    id: 'filesystem.crontab_permissions',
    title: '/etc/crontab is owned by root and private',
    category: 'filesystem',
    severity: 'low',
    expected: 'uid 0, mode <= 0600',
    pass: ['uid 0, mode 0600', '/etc/crontab: owner=0 mode=0600'],
    fail: ['uid 0, mode 0644', '/etc/crontab: owner=0 mode=0644'],
    notApplicable: '/etc/crontab not found',
  },
];
