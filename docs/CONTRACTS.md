# Driftwatch — shared contracts

Driftwatch is a Linux fleet compliance platform. A small Go agent on each host runs
CIS-Benchmark-style hardening checks and reports results to a central API. The API
stores scan history, computes a compliance score per host, and records **drift** (a
check that changed status between two consecutive scans). A React dashboard shows
fleet posture, per-host score over time, and what changed.

This file is the single source of truth that every component is built against.
`contracts/openapi.yaml` is the machine-readable HTTP contract. If the two disagree,
`openapi.yaml` wins for HTTP shapes; this file wins for everything else.

**Do not change this file or anything in `contracts/`.** If a contract is wrong or
missing something, implement the closest faithful version and list the proposed change
in your final report. The lead integrates contract changes.

## Repository layout and ownership

| Path | Owner | Notes |
|---|---|---|
| `contracts/**`, `docs/CONTRACTS.md`, `docs/ARCHITECTURE.md`, `docs/adr/**`, `README.md` | lead | read-only for everyone else |
| `agent/**` | agent team | Go module `github.com/TahaUsama210/driftwatch/agent` |
| `deploy/hosts/**` | agent team | simulated Linux hosts for the demo fleet |
| `api/**` | api team | Spring Boot service |
| `web/**` | web team | React dashboard, including its nginx config and Dockerfile |
| `docker-compose.yml`, `.github/**`, `Makefile`, `scripts/**`, `.env.example`, root `.gitignore`, `.gitattributes`, `.editorconfig` | platform team | |

Never edit files outside your paths. Never commit, push, or create branches — the lead
does all git operations. Never touch Docker containers, volumes, or networks whose names
do not start with `driftwatch` (another project, `scholaria-*`, is running on this machine).

## Ports (host side)

Ports already in use on this machine and **off limits**: 5432, 5173, 5174, 8000, 8090,
8100, 15432, 15434, 25432, and anything else `Get-NetTCPConnection -State Listen` shows.

| What | Host port | Container port |
|---|---|---|
| Postgres (compose, for local debugging) | 35432 | 5432 |
| API | 8480 | 8080 |
| Web (nginx, serves SPA, proxies the `/api/` and `/actuator/health` path prefixes to the API) | 8400 | 8080 |
| Web dev server (`npm run dev`, proxies `/api` → `http://localhost:8480`) | 5180 | — |
| API team's private dev Postgres (`driftwatch-dev-db`) | 35433 | 5432 |

Tests must use Testcontainers / random ports, never fixed host ports.

## Toolchain and versions

- Go 1.27 (installed at `C:\Program Files\Go\bin`; open a fresh shell or prepend it to PATH).
- Java 25 (LTS), Spring Boot 4.1.2, Maven via the Maven Wrapper (`mvnw` / `mvnw.cmd`).
- Postgres 18 (`postgres:18-alpine`).
- Node 22 LTS locally; React 19, Vite, TypeScript, Tailwind CSS v4, TanStack Query v5.
- Pin exact versions everywhere (lockfiles committed, Docker base images pinned to a
  minor version at least). Only use releases that have been out for at least two weeks.

## Commands each component must support (CI calls exactly these)

| Component | Working dir | Commands |
|---|---|---|
| agent | `agent/` | `go vet ./...`, `go test -race -coverprofile=coverage.out ./...`, `golangci-lint run` (config in `agent/.golangci.yml`), `go build ./cmd/driftwatch-agent` |
| api | `api/` | `./mvnw -B verify` (formatting check, unit tests, Testcontainers integration tests, JaCoCo report at `target/site/jacoco/jacoco.xml`) |
| web | `web/` | `npm ci`, `npm run lint`, `npm run typecheck`, `npm test` (non-watch), `npm run build`, `npm run gen:api` (regenerates types from `../contracts/openapi.yaml`; CI fails if the committed file differs) |

Docker images (each built with its component directory as the build context unless noted):

| Image | Dockerfile | Context |
|---|---|---|
| `driftwatch-agent` | `agent/Dockerfile` | `agent/` |
| `driftwatch-api` | `api/Dockerfile` | `api/` |
| `driftwatch-web` | `web/Dockerfile` | `web/` |
| simulated hosts | `deploy/hosts/<name>/Dockerfile` | **repo root** (they build the agent from `agent/`) |

The api and web images run as a non-root user. The agent image and the simulated hosts
run as root because the agent must read `/etc/shadow` — that is realistic for a
compliance agent, and each Dockerfile says so.

## Configuration (environment variables)

API:

| Variable | Default | Meaning |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:35433/driftwatch` | JDBC URL |
| `DB_USER` / `DB_PASSWORD` | `driftwatch` / `driftwatch` | |
| `DRIFTWATCH_ENROLLMENT_TOKEN` | none — required, min 24 chars; startup fails without it | shared secret agents present to enroll |
| `DRIFTWATCH_STALE_AFTER` | `PT5M` | host is `STALE` when `lastSeenAt` is older than this |
| `DRIFTWATCH_CORS_ORIGINS` | empty | comma-separated; dev sets `http://localhost:5180` |
| `SERVER_PORT` | `8080` | |

Agent:

| Variable / flag | Default | Meaning |
|---|---|---|
| `DRIFTWATCH_SERVER_URL` / `--server` | none | base URL, e.g. `http://api:8080` |
| `DRIFTWATCH_ENROLLMENT_TOKEN` / `--enrollment-token` | none | used only for the first enrollment |
| `DRIFTWATCH_INTERVAL` / `--interval` | `5m` | Go duration between scans; demo hosts use `30s` |
| `DRIFTWATCH_STATE_DIR` / `--state-dir` | `/var/lib/driftwatch` | stores `state.json` (hostId, agentToken, machineId fallback), mode 0600 |
| `DRIFTWATCH_ROOT` / `--root` | `/` | filesystem root to scan (lets you scan a mounted image) |
| `DRIFTWATCH_PROC` / `--proc` | `/proc` | procfs root for sysctl reads |
| `DRIFTWATCH_LOG_FORMAT` | `json` | `json` or `text` |
| `DRIFT_PERIOD` | `120` | demo host `alpine-drifting` only (read by its entrypoint, not the agent): seconds between `PermitRootLogin` flips |

CLI: `driftwatch-agent run` (enroll if needed, then scan every interval until SIGINT/SIGTERM),
`driftwatch-agent scan [--format table|json]` (one local scan, prints results, no network),
`driftwatch-agent checks` (prints the catalog), `driftwatch-agent version`.

## Domain rules

### Check statuses

`PASS`, `FAIL`, `ERROR` (the check could not be evaluated, e.g. permission denied or a
parse error — evidence carries the error), `NOT_APPLICABLE` (the thing being checked
does not exist on this host, e.g. no `sshd_config` because SSH is not installed).

### Severity weights

`low` = 1, `medium` = 2, `high` = 3, `critical` = 5.

### Compliance score

```
score = 100 × Σ weight(PASS) / Σ weight(PASS ∪ FAIL ∪ ERROR)
```

`NOT_APPLICABLE` is excluded. `ERROR` counts as failing (an unverifiable control is not
a compliant control). Rounded half-up to one decimal. `null` when nothing is applicable.
The **API computes the authoritative score**; the agent may print the same formula
locally in `scan` output.

### Drift

Computed by the API when a scan is ingested, by comparing it with the **previous scan
of the same host** (by `finishedAt`). Only check IDs present in both scans are compared.
The first scan of a host is its baseline and produces no events.

| from → to | direction |
|---|---|
| `PASS` or `NOT_APPLICABLE` → `FAIL` or `ERROR` | `REGRESSED` |
| `FAIL` or `ERROR` → `PASS` | `RESOLVED` |
| any other status change (e.g. `FAIL` → `ERROR`, `PASS` → `NOT_APPLICABLE`) | `CHANGED` |

Same status with different evidence is **not** drift.

### Host identity and auth

1. The agent reads `/etc/machine-id` (under `--root`). If it is missing or empty it
   generates a random UUID once and persists it in `state.json`.
2. First run: `POST /api/v1/agents/enroll` with `Authorization: Bearer <enrollment token>`.
   The API upserts the host by `machineId` and returns `{hostId, agentToken}`.
   `201` for a new host, `200` for a re-enrolled host (its old token stops working).
3. Every scan: `POST /api/v1/scans` with `Authorization: Bearer <agentToken>`.
   On `401` the agent discards its token and re-enrolls once.
4. The API stores only the SHA-256 hash of agent tokens and compares secrets in constant
   time. Agent tokens are 32 random bytes, base64url without padding, prefixed `dwa_`.
5. Read endpoints (`GET /api/v1/**`) are unauthenticated in phase 1 — documented as a
   known tradeoff; phase 2 adds dashboard auth.

### Idempotency

`scanId` is generated by the agent (UUIDv4) before submission. Re-submitting the same
`scanId` for the same host returns `200` with the stored summary and changes nothing;
the same `scanId` from a different host is `409`. The agent retries failed submissions
with exponential backoff and jitter (1s base, 60s cap, give up after 5 attempts and
keep the next interval's scan).

### Validation limits (API rejects with 400 problem+json)

- `results`: 1–500 items, `checkId` unique within a scan, matches `^[a-z0-9_.]{3,64}$`.
- `title` ≤ 200 chars, `expected`/`actual` ≤ 200, `evidence` ≤ 1000, `hostname` ≤ 255.
- `startedAt` ≤ `finishedAt`; `finishedAt` no more than 5 minutes in the future.
- Request body ≤ 1 MiB.

### Errors

All errors are RFC 9457 `application/problem+json` with `type`, `title`, `status`,
`detail`, and for validation errors an `errors: [{field, message}]` array. Never leak
stack traces or SQL.

### Timestamps and IDs

RFC 3339 UTC with `Z` (e.g. `2026-11-03T14:05:00Z`). All IDs are UUIDs.

## Check catalog (phase 1)

The agent owns the implementation; these IDs, titles, categories and severities are
fixed so the API tests and web fixtures match. Categories: `ssh`, `accounts`, `network`,
`kernel`, `filesystem`. Checks are *modeled on* CIS Benchmark Level 1 controls; do not
cite specific CIS section numbers.

| ID | Title | Category | Severity | Passes when |
|---|---|---|---|---|
| `ssh.permit_root_login` | SSH root login is disabled | ssh | high | effective `PermitRootLogin` is `no` |
| `ssh.password_authentication` | SSH password authentication is disabled | ssh | high | `PasswordAuthentication no` |
| `ssh.permit_empty_passwords` | SSH rejects empty passwords | ssh | critical | `PermitEmptyPasswords no` (also the OpenSSH default when unset) |
| `ssh.max_auth_tries` | SSH limits authentication attempts | ssh | medium | `MaxAuthTries` ≤ 4 |
| `ssh.x11_forwarding` | SSH X11 forwarding is disabled | ssh | low | `X11Forwarding no` |
| `ssh.login_grace_time` | SSH login grace time is 60s or less | ssh | low | `LoginGraceTime` between 1 and 60 seconds |
| `ssh.idle_timeout` | SSH idle sessions time out | ssh | low | `ClientAliveInterval` between 1 and 900 |
| `ssh.config_permissions` | sshd_config is owned by root and not group/world accessible | ssh | medium | owner uid 0, mode & 0077 == 0 |
| `accounts.shadow_permissions` | /etc/shadow is protected | accounts | high | owner uid 0, mode & 0137 == 0 (at most 0640) |
| `accounts.passwd_permissions` | /etc/passwd is not writable by group or others | accounts | medium | owner uid 0, mode & 0133 == 0 (at most 0644) |
| `accounts.uid0_only_root` | Only root has UID 0 | accounts | critical | exactly one `/etc/passwd` entry with UID 0, named `root` |
| `accounts.no_empty_passwords` | No account has an empty password | accounts | critical | no `/etc/shadow` entry with an empty password field |
| `accounts.pass_max_days` | Passwords expire within 365 days | accounts | medium | `/etc/login.defs` `PASS_MAX_DAYS` between 1 and 365 |
| `accounts.pass_min_days` | Passwords have a minimum age | accounts | low | `PASS_MIN_DAYS` ≥ 1 |
| `accounts.pass_warn_age` | Users are warned before password expiry | accounts | low | `PASS_WARN_AGE` ≥ 7 |
| `network.ip_forwarding_disabled` | IPv4 forwarding is disabled | network | medium | `net.ipv4.ip_forward` = 0 |
| `network.icmp_redirects_rejected` | ICMP redirects are not accepted | network | medium | `net.ipv4.conf.all.accept_redirects` = 0 |
| `network.source_routing_rejected` | Source-routed packets are not accepted | network | medium | `net.ipv4.conf.all.accept_source_route` = 0 |
| `network.syn_cookies_enabled` | TCP SYN cookies are enabled | network | medium | `net.ipv4.tcp_syncookies` = 1 |
| `kernel.aslr_enabled` | Full address space layout randomization is enabled | kernel | high | `kernel.randomize_va_space` = 2 |
| `filesystem.crontab_permissions` | /etc/crontab is owned by root and private | filesystem | low | owner uid 0, mode & 0077 == 0; `NOT_APPLICABLE` if absent |

`sshd_config` evaluation: honour `Include` directives (glob, relative to `/etc/ssh`) and
OpenSSH's **first-match-wins** rule for keywords; ignore `Match` blocks for phase 1
(document it). Keywords are case-insensitive. Apply OpenSSH defaults when a keyword is
unset (`PermitRootLogin prohibit-password`, `PasswordAuthentication yes`,
`PermitEmptyPasswords no`, `MaxAuthTries 6`, `X11Forwarding no`, `LoginGraceTime 120`,
`ClientAliveInterval 0`). If `sshd_config` is absent every `ssh.*` check is `NOT_APPLICABLE`.

## Demo fleet (agent team builds, platform team wires into compose)

At least four simulated hosts on different distros with deliberately different postures,
so the dashboard has a realistic spread:

| Service | Base | Posture |
|---|---|---|
| `host-ubuntu-hardened` | `ubuntu:24.04` | nearly everything passes |
| `host-debian-legacy` | `debian:12.14` (a pinned point release, since `debian:12` floats) | weak SSH config, lax login.defs, an extra UID-0 account |
| `host-rocky-web` | `rockylinux/rockylinux:9.8` (the Docker Hub `rockylinux` library image stopped updating in 2024) | mixed; `net.ipv4.ip_forward=1` via compose `sysctls` |
| `host-alpine-drifting` | `alpine:3.22` | starts mostly compliant; a background loop flips one setting (e.g. `PermitRootLogin`) every few minutes, so drift events appear live |

Network sysctls are per network namespace, so compose `sysctls:` changes them per
container. `kernel.randomize_va_space` is not namespaced and will read the Docker host's
value — that is fine. Writing `net.ipv4.ip_forward` makes the kernel reset
`accept_redirects` to the opposite value, and the container runtime applies sysctls in
no fixed order, so compose never sets both on one host (see the comment in
`docker-compose.yml`). Hosts with forwarding off therefore fail
`network.icmp_redirects_rejected`, as a real host with only `ip_forward=0` would.

### Edge cases decided by the agent team

- `/etc/login.defs` or one of its keys missing → `FAIL` ("not set"); missing
  `/etc/passwd` or `/etc/shadow` → `ERROR`.
- A malformed `/etc/machine-id` (e.g. `uninitialized`) is treated as missing.
- `--interval` must be at least `10s`. A 4xx on enrollment exits with code 1.
- `scan --format json` prints exactly a `ScanReport` (no score field).
- Simulated hosts have no machine-id, so their identity lives in the
  `/var/lib/driftwatch` volume; compose gives agents a 10s stop grace period.

## Metrics (API, exposed at `/actuator/prometheus`)

- `driftwatch_scans_ingested_total{outcome="created|duplicate|rejected"}`
- `driftwatch_scan_ingest_duration_seconds` (histogram)
- `driftwatch_drift_events_total{direction}`
- `driftwatch_hosts{status="online|stale"}` (gauge)
- `driftwatch_fleet_average_score` (gauge)
- plus Spring Boot's default HTTP server metrics.

Health: `/actuator/health/liveness`, `/actuator/health/readiness` (readiness includes the DB).
OpenAPI from the running app: `/v3/api-docs`; Swagger UI at `/swagger-ui.html`.

## Quality bar (applies to everyone)

This is a portfolio project that hiring managers will read line by line. Write it the way
a senior engineer would on a production team:

- Small, cohesive packages; no dead code, no commented-out code, no TODOs without context.
- Tests that prove behaviour (table-driven / parameterised where natural), including
  failure paths. Test names describe behaviour.
- Structured logging; no secrets in logs (never log tokens).
- Clear error handling; no swallowed errors.
- Every claim in a README or comment must be true of the code. No invented numbers.
