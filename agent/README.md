# driftwatch-agent

The Driftwatch agent is a single static Go binary that runs on a Linux host,
evaluates 21 hardening checks modeled on CIS Benchmark Level 1 controls, and
reports the results to the Driftwatch API. It uses only the Go standard
library.

- `run` enrolls the host once, then scans and submits a report immediately and
  every interval until it receives SIGINT or SIGTERM.
- `scan` runs one local scan and prints it. It never touches the network.

## Quick start

```sh
# Build and look at the local host
go build ./cmd/driftwatch-agent
sudo ./driftwatch-agent scan

# Run against the API
sudo DRIFTWATCH_SERVER_URL=http://localhost:8480 \
     DRIFTWATCH_ENROLLMENT_TOKEN=... \
     ./driftwatch-agent run

# Scan the Docker host from a container, read-only
docker build -t driftwatch-agent:dev .
docker run --rm -v /:/host:ro -e DRIFTWATCH_ROOT=/host -e DRIFTWATCH_PROC=/host/proc \
  driftwatch-agent:dev scan --format table
```

## Command line

```
driftwatch-agent run [flags]       enroll if needed, then scan and report every interval
driftwatch-agent scan [flags]      run one local scan and print the results (no network)
driftwatch-agent checks            print the check catalog
driftwatch-agent version           print version, commit, Go version and platform
```

Flags take precedence over environment variables, which take precedence over
defaults.

| Flag | Environment variable | Default | Commands | Meaning |
|---|---|---|---|---|
| `--server` | `DRIFTWATCH_SERVER_URL` | none (required) | `run` | API base URL, e.g. `http://api:8080` |
| `--enrollment-token` | `DRIFTWATCH_ENROLLMENT_TOKEN` | none | `run` | shared secret, used only when the agent has no valid agent token |
| `--interval` | `DRIFTWATCH_INTERVAL` | `5m` | `run` | Go duration between scans; at least `10s` |
| `--state-dir` | `DRIFTWATCH_STATE_DIR` | `/var/lib/driftwatch` | `run` | directory for `state.json` |
| `--root` | `DRIFTWATCH_ROOT` | `/` | `run`, `scan` | filesystem root to scan |
| `--proc` | `DRIFTWATCH_PROC` | `/proc` | `run`, `scan` | procfs to read kernel parameters from |
| `--format` | | `table` | `scan` | `table` or `json` |
| | `DRIFTWATCH_LOG_FORMAT` | `json` | `run` | `json` or `text`; logs go to stderr at level INFO |

Prefer the environment variable for the enrollment token: command-line
arguments are visible to every local user through `ps`.

`scan --format json` prints exactly the `ScanReport` that `run` would submit.
`scan --format table` prints one row per check plus the compliance score
computed with the contract's formula. `scan` exits 0 whatever the results are.

Exit codes: `0` success (including shutdown on a signal), `1` runtime error,
`2` usage or configuration error.

## Check catalog

IDs, titles, categories and severities are fixed by `docs/CONTRACTS.md`, and
`TestCatalogMatchesContract` pins them.

| ID | Severity | Passes when |
|---|---|---|
| `ssh.permit_root_login` | high | effective `PermitRootLogin` is `no` |
| `ssh.password_authentication` | high | effective `PasswordAuthentication` is `no` |
| `ssh.permit_empty_passwords` | critical | effective `PermitEmptyPasswords` is `no` |
| `ssh.max_auth_tries` | medium | effective `MaxAuthTries` ≤ 4 |
| `ssh.x11_forwarding` | low | effective `X11Forwarding` is `no` |
| `ssh.login_grace_time` | low | effective `LoginGraceTime` is 1–60 seconds |
| `ssh.idle_timeout` | low | effective `ClientAliveInterval` is 1–900 seconds |
| `ssh.config_permissions` | medium | `/etc/ssh/sshd_config` has owner uid 0 and no group/other bits |
| `accounts.shadow_permissions` | high | `/etc/shadow` has owner uid 0 and mode 0640 or stricter |
| `accounts.passwd_permissions` | medium | `/etc/passwd` has owner uid 0 and mode 0644 or stricter |
| `accounts.uid0_only_root` | critical | exactly one `/etc/passwd` entry has UID 0, and it is `root` |
| `accounts.no_empty_passwords` | critical | no `/etc/shadow` entry has an empty password field |
| `accounts.pass_max_days` | medium | `/etc/login.defs` `PASS_MAX_DAYS` is 1–365 |
| `accounts.pass_min_days` | low | `PASS_MIN_DAYS` ≥ 1 |
| `accounts.pass_warn_age` | low | `PASS_WARN_AGE` ≥ 7 |
| `network.ip_forwarding_disabled` | medium | `net.ipv4.ip_forward` = 0 |
| `network.icmp_redirects_rejected` | medium | `net.ipv4.conf.all.accept_redirects` = 0 |
| `network.source_routing_rejected` | medium | `net.ipv4.conf.all.accept_source_route` = 0 |
| `network.syn_cookies_enabled` | medium | `net.ipv4.tcp_syncookies` = 1 |
| `kernel.aslr_enabled` | high | `kernel.randomize_va_space` = 2 |
| `filesystem.crontab_permissions` | low | `/etc/crontab` has owner uid 0 and no group/other bits |

Every result carries `expected`, `actual` and `evidence`; the evidence names
the file and line that decided the value (for example
`/etc/ssh/sshd_config.d/10-hardening.conf:2: PermitRootLogin no`).

When a check cannot reach a verdict:

- **NOT_APPLICABLE**: every `ssh.*` check when `/etc/ssh/sshd_config` does not
  exist, and `filesystem.crontab_permissions` when `/etc/crontab` does not
  exist.
- **ERROR** (counts as failing in the score): the file or kernel parameter
  cannot be read, a file is malformed (an `/etc/passwd` line with fewer than
  seven fields, a non-numeric UID or `login.defs` value, an sshd syntax error),
  or sshd would reject the value (`PermitRootLogin maybe`). The evidence
  carries the error. A missing `/etc/passwd` or `/etc/shadow` is also an
  ERROR, because the control cannot be verified.
- A missing `/etc/login.defs`, or a missing key in it, is a **FAIL** with
  actual `not set`: shadow-utils then applies no password aging at all.

`expected` and `actual` are cut to 200 bytes and `evidence` to 1000 bytes at a
UTF-8 boundary, so a report always passes the API's length limits. A check
that panics is reported as ERROR instead of stopping the scan. Evidence never
contains password hashes.

## Design notes

### Scanning a mounted root

All file access goes through `internal/hostfs`, which maps host paths onto
`--root`. Symbolic links are resolved the way the scanned host would resolve
them: absolute targets are re-rooted under `--root` and `..` cannot climb above
it. This matters when scanning a mounted root, where `/etc/os-release` is often
a symlink. Error messages name host paths (`/etc/shadow`), not mount paths.
Only regular files are read, with an 8 MiB cap, so a FIFO or device planted
in place of a configuration file cannot block or flood a scan; it is an
ERROR instead. Ownership comes from `syscall.Stat_t`, so
on non-Unix platforms the permission checks report ERROR. The agent targets
Linux; the module still builds and its tests pass on Windows.

The hostname comes from the kernel (`os.Hostname`) when `--root` is `/` and
from `<root>/etc/hostname` otherwise. The OS is read from `/etc/os-release`,
falling back to `/usr/lib/os-release` and then to the os-release(5) defaults
(`linux`, `Linux`). The kernel release is read from `<proc>/sys/kernel/osrelease`.

### sshd_config semantics

`internal/sshd` follows sshd_config(5) and OpenSSH's `servconf.c`:

- Keywords are case-insensitive, and so are yes/no style values. A keyword is
  separated from its arguments by whitespace and/or one `=`. Arguments may be
  quoted, and an unquoted `#` starts a comment.
- **First match wins**: the first value seen for a keyword is the one sshd uses.
- `Include` is expanded in place. Each argument is a glob; relative patterns
  resolve against `/etc/ssh`; matched files are read in lexical order; as in
  glob(3), `*` does not match a leading `.`; a pattern that matches nothing is
  fine; nesting stops at 16 levels. Distributions put
  `Include /etc/ssh/sshd_config.d/*.conf` at the top of the main file, so
  drop-ins override what follows. Because files that include each other can
  expand exponentially within 16 levels, the agent also stops after reading
  256 files or 8 MiB in total and reports the SSH checks as ERROR.
- Unset keywords take the OpenSSH defaults (`PermitRootLogin
  prohibit-password`, `PasswordAuthentication yes`, `PermitEmptyPasswords no`,
  `MaxAuthTries 6`, `X11Forwarding no`, `LoginGraceTime 120`,
  `ClientAliveInterval 0`), and the evidence says so.
- Time values use sshd's format (`90`, `2m`, `1h30m`).
- **Match blocks are not evaluated** in phase 1, because their effect depends
  on the connecting user and address. A block runs from its `Match` line to
  the next `Match` or the end of the file that contains it; its directives,
  including `Include`, are skipped. `Match all` matches every connection, so
  it ends the block and what follows is global again. When a Match block sets
  the keyword a check looks at, the evidence says so (`1 Match block override(s) not evaluated,
  first at /etc/ssh/sshd_config:120`), so a PASS is never silently misleading.

On the four demo hosts the effective values agree with `sshd -T` for every
keyword the checks use (`sshd -T` prints `without-password`, its alias for
`prohibit-password`).

`login.defs` follows shadow-utils instead: keys are case-sensitive and the
last occurrence wins. The value is the rest of the line, minus leading blanks
and quotes, so there are no trailing comments: shadow-utils cannot parse
`PASS_MAX_DAYS 90 # policy` and ignores it, and the agent reports it as an
ERROR rather than a PASS. A key with no value is ignored, so it is `not set`.

### Why the agent runs as root

`/etc/shadow` is readable only by root, and the permission checks need the
real owner and mode of system files. The container image therefore runs as
root (`USER 0:0`) by necessity. The agent only reads: it writes nothing outside
`--state-dir`, and when it scans a mounted host root that mount should be
read-only (`-v /:/host:ro`).

### Enrollment, retries and idempotency

1. The machine ID is `/etc/machine-id` when it holds a valid ID (32 lowercase
   hex characters). If the file is missing, empty or `uninitialized`, a random
   UUIDv4 is generated once and saved in `state.json` *before* it is sent. Any
   other read error stops the agent rather than silently enrolling a duplicate
   host.
2. With no agent token, the agent enrolls with the enrollment token and saves
   the returned host ID and agent token.
3. Each scan gets a fresh UUIDv4 `scanId` and is submitted with the agent
   token. Retries resend the identical body, so the API sees the same `scanId`
   and answers a duplicate with 200 (logged as `duplicate: true`).
4. Transport errors and 5xx responses are retried with exponential backoff and
   full jitter: before retry *n* the agent waits a random duration in
   `[0, min(60s, 1s·2ⁿ⁻¹)]`, and gives up after 5 attempts. The scan is then
   dropped and the next interval's scan goes ahead. Each attempt has a 30 s
   HTTP timeout.
5. A 4xx response is never retried. A 401 on submission means the token was
   rotated, so the agent discards it, re-enrolls **once** and resubmits the same
   report. Any other 4xx on submission (400, 409, 413) is logged and that scan
   is dropped. A 4xx on enrollment (wrong enrollment token, invalid request) stops
   the agent with exit code 1, because retrying cannot fix it. So does failing
   to read or write `state.json`.

### Shutdown

SIGINT or SIGTERM cancels the run loop. No new request or backoff wait starts
after that. A request already on the wire gets up to 5 s to finish, so an
enrollment or scan the API has accepted is not lost. With no request in
flight, the agent exits immediately.

### Token handling

Tokens are held as `secret.Token`, which prints `[REDACTED]` through `fmt`,
`slog` and `encoding/json` (slog's JSON handler uses `encoding/json` for
nested structs). Only the `Authorization` header and the state file call
`Reveal()`. Tests check that no token appears in log output.

`state.json` holds the host ID, agent token and, if generated, the machine ID.
The agent creates the state directory with mode 0700 if it is missing and
writes the file with mode 0600. It is replaced
atomically: written to a temporary file in the same directory, fsynced,
renamed over the old file, and then the directory is fsynced. A crash leaves
the old state or the new one, never a torn file.

## Development

```sh
go vet ./...
go test ./...                                    # works on Linux and Windows
go test -race -coverprofile=coverage.out ./...   # -race needs cgo (Linux, or the golang image)
golangci-lint run                                # v2, configured in .golangci.yml
go build ./cmd/driftwatch-agent
```

Without a local cgo toolchain, run the race tests in a container:

```sh
docker run --rm -v "$PWD":/src -w /src golang:1.27 go test -race ./...
```

Version and commit are injected at link time:

```sh
go build -ldflags "-X main.version=0.1.0 -X main.commit=$(git rev-parse --short HEAD)" ./cmd/driftwatch-agent
```

Tests are table-driven and use an in-memory filesystem
(`internal/hostfs/hostfstest`), so every check's PASS, FAIL, NOT_APPLICABLE
and ERROR paths run on any OS. The client and runner are tested against
`httptest` servers: retries, backoff and giving up, same `scanId` on retry,
duplicates, 401 re-enrollment, shutdown during a request and during a backoff
wait, and token redaction in logs.

### Layout

| Package | Responsibility |
|---|---|
| `cmd/driftwatch-agent` | `main`: signal handling and build info |
| `internal/cli` | commands, flags, environment variables, output formats |
| `internal/runner` | the `run` loop: identity, enrollment, re-enrollment, scheduling |
| `internal/scan` | assembles a `ScanReport` (facts, results, timestamps, scan ID) |
| `internal/checks` | the catalog and each check's evaluation |
| `internal/sshd` | sshd_config evaluator |
| `internal/facts` | hostname, os-release, kernel, architecture, machine ID |
| `internal/hostfs` | read-only access to a host root, with chroot-style symlink resolution |
| `internal/score` | compliance score and status counts |
| `internal/client` | API client: retries, backoff, error mapping |
| `internal/state` | atomic, private `state.json` |
| `internal/secret` | self-redacting token type |
