# Driftwatch — shared contracts

Driftwatch is a Linux fleet compliance platform. A small Go agent on each host runs
CIS-Benchmark-style hardening checks and reports results to a central API. The API
stores scan history, computes a compliance score per host, and records **drift** (a
check that changed status between two consecutive scans). A React dashboard shows
fleet posture, per-host score over time, and what changed.

This file is the single source of truth that every component is built against. Phase 2
(identity, observability, Kubernetes, Terraform, supply chain) is specified in
[Phase 2](#phase-2) at the end; where it changes a phase 1 rule, phase 2 wins.
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
5. Read endpoints (`GET /api/v1/**`) and decommission need a dashboard JWT (phase 2; see
   "Identity" below). They were unauthenticated in phase 1.

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

---

## Phase 2

Phase 2 adds dashboard login (OIDC), per-host metrics with alerting, a Kubernetes
deployment, an AWS test fleet in Terraform, and a signed, scanned image supply chain.

### Phase 2 ownership

| Path | Owner |
|---|---|
| `contracts/**`, `docs/**`, `README.md`, `deploy/helm/driftwatch/files/keycloak/driftwatch-realm.json` | lead |
| `api/**` | api team |
| `web/**` | web team |
| `deploy/observability/**` | observability team |
| `deploy/helm/**` (except the realm file), `deploy/k3d/**` | kubernetes team |
| `infra/terraform/**` | terraform team |
| `docker-compose.yml`, `.github/**`, `Makefile`, `scripts/**`, `.env.example`, root dotfiles, `agent/Dockerfile` | platform team |

The phase 1 rules still apply: never edit outside your paths, never commit/push/branch/tag,
never touch Docker resources not prefixed `driftwatch` (or k3d clusters not named
`driftwatch`), and list proposed contract changes in your final report.

### Phase 2 ports (host side, all bound to 127.0.0.1)

| What | Port |
|---|---|
| Keycloak | none: reached through the web nginx at `/auth` (same origin as the dashboard) |
| Prometheus | 39090 |
| Alertmanager | 39093 |
| Grafana | 33000 |
| k3d ingress (Traefik load balancer) | 38080 |
| k3d Kubernetes API | 36443 |

### Identity (OIDC with Keycloak)

- Keycloak `quay.io/keycloak/keycloak:26.8.1`. The realm is imported from
  `deploy/helm/driftwatch/files/keycloak/driftwatch-realm.json`, the single source for both
  compose (mounted read-only) and Helm (`.Files.Get`). The lead has verified that it
  imports and issues the tokens described here.
- Keycloak runs under the path `/auth` behind the web nginx, so the SPA, the IdP and the
  API share one origin (no CORS; silent renew in an iframe works). Keycloak settings:
  `KC_HTTP_RELATIVE_PATH=/auth`, `KC_HOSTNAME=<public base>/auth`,
  `KC_PROXY_HEADERS=xforwarded`, `KC_HTTP_ENABLED=true`, `KC_HEALTH_ENABLED=true`
  (management port 9000, readiness at `:9000/auth/health/ready`). The demo runs
  `start-dev --import-realm` (embedded H2): local use only, and the docs must say so.
- **Public base URL** per environment: compose `http://localhost:8400`, k3d
  `http://localhost:38080`, Vite dev server `http://localhost:5180` (which uses compose's
  Keycloak at the 8400 issuer).
- **Issuer** = `<public base>/auth/realms/driftwatch`. Because `KC_HOSTNAME` is fixed,
  tokens carry this issuer however Keycloak was reached.

| Realm object | Details |
|---|---|
| role `viewer` | read everything |
| role `admin` | composite: includes `viewer`, plus decommission hosts |
| client `driftwatch-web` | public, Authorization Code + PKCE S256 only, redirect URIs on ports 8400/5180/38080 |
| client `driftwatch-smoke-viewer` | confidential, client credentials only, secret `smoke-viewer-demo-secret`, role `viewer` |
| client `driftwatch-smoke-admin` | confidential, client credentials only, secret `smoke-admin-demo-secret`, roles `viewer` + `admin` |
| user `viewer` / password `viewer-demo` | role `viewer` |
| user `admin` / password `admin-demo` | role `admin` |

All clients have `fullScopeAllowed: false` with explicit scope mappings. Access tokens
last 300 s, carry roles in `realm_access.roles`, and have `aud` containing
`driftwatch-api`. The demo secrets and passwords are for the localhost-bound demo only.

**API configuration** (new env vars):

| Variable | Default | Meaning |
|---|---|---|
| `DRIFTWATCH_OIDC_ISSUER` | none, required | exact `iss` value to accept |
| `DRIFTWATCH_OIDC_JWKS_URI` | `${DRIFTWATCH_OIDC_ISSUER}/protocol/openid-connect/certs` | where to fetch signing keys; compose and k8s point it at the internal Keycloak service |
| `DRIFTWATCH_OIDC_AUDIENCE` | `driftwatch-api` | required `aud` entry |

The API must start and pass readiness while Keycloak is down: keys are fetched lazily
and cached. Readiness does not depend on Keycloak.

**Authorization matrix**

| Endpoint | Credential | Role |
|---|---|---|
| `GET /api/v1/**` | dashboard JWT | `viewer` |
| `DELETE /api/v1/hosts/{hostId}` | dashboard JWT | `admin` |
| `POST /api/v1/agents/enroll` | enrollment token | — |
| `POST /api/v1/scans` | agent token | — |
| `/actuator/health/**`, `/actuator/prometheus`, `/v3/api-docs/**`, `/swagger-ui/**` | none | — |

A JWT is never accepted on the agent endpoints, and an agent token is never accepted on
dashboard endpoints. A missing, malformed, expired, wrongly signed or wrong-issuer or
wrong-audience token is `401` with `WWW-Authenticate: Bearer` and problem+json. A valid
token without the role is `403` problem+json. If what a credential is checked against is
unreachable (the JWKS endpoint for a JWT, the database for an agent token), the API
answers `503` problem+json: the credential may be valid, so it must not be a `401`. Like
`500`, this generic server-side condition isn't listed per operation in the OpenAPI
document. Log the actor's `sub` and `preferred_username` on every decommission
(structured audit log line), never the token.

CORS (only relevant to the Vite dev server, since production is same-origin) allows
`GET` and `DELETE` with the `Authorization` header.

**Decommission** (`DELETE /api/v1/hosts/{hostId}`): deletes the host and, in the same
transaction, its scans, results and drift events. Its agent token stops working
immediately. Increments `driftwatch_hosts_decommissioned_total`, and the host's per-host
metric series disappear on the next gauge refresh.

**Web runtime configuration.** nginx serves `GET /config.json`, rendered at container
start from env: `{"oidc":{"authority":"<OIDC_AUTHORITY>","clientId":"<OIDC_CLIENT_ID>"}}`.
Defaults: `OIDC_AUTHORITY=http://localhost:8400/auth/realms/driftwatch`,
`OIDC_CLIENT_ID=driftwatch-web`. nginx also proxies the `/auth/` prefix to
`KEYCLOAK_UPSTREAM` (default `http://keycloak:8080`) with `X-Forwarded-*` headers. The
SPA uses Authorization Code + PKCE (e.g. `oidc-client-ts`), keeps tokens out of
`localStorage`, renews silently, sends `Authorization: Bearer` on every API call, and
re-authenticates on 401. Admin-only UI (decommission) is hidden for viewers, but the API
is the enforcement point. Redirect paths: `/signin-callback` (sign-in) and
`/silent-renew.html` (silent renew iframe). At the dashboard origin, `/auth/admin` and
`/auth/realms/master`, with or without anything after them, return `404`, so the
Keycloak admin console isn't exposed there; reach it by port-forwarding Keycloak
directly. nginx forwards `X-Forwarded-*` headers it receives (to keep what an ingress
such as Traefik set), so where nginx is the edge (compose), a client can choose the
address Keycloak logs for it. Keycloak's URLs are pinned by `KC_HOSTNAME`, and brute-force
protection counts failures per user, so neither depends on these headers. nginx serves `/config.json` with
`Cache-Control: no-store` and writes it only under `/tmp` or `/etc/nginx/conf.d`, the
image's two writable paths (uid 101). With a read-only root FS, Kubernetes mounts
`emptyDir`s there; a Docker tmpfs there would need `uid=101,gid=101`. Mock mode (`dev:mock`) bypasses login with a fake user whose
role can be switched for demos.

### Per-host metrics (API)

Refreshed on the existing gauge schedule (15 s). Label `hostname` is for humans and
`host_id` for identity. Cardinality is one series per host per metric, which is
acceptable for fleets of hundreds of hosts (document this).

- `driftwatch_host_score{host_id,hostname}`: latest scan's score. No series while the score is null.
- `driftwatch_host_last_seen_timestamp_seconds{host_id,hostname}`: Unix seconds.
- `driftwatch_host_failing_checks{host_id,hostname,severity}`: FAIL + ERROR on the latest scan; all four severities present (0 when none). Absent until the host's first scan.
- `driftwatch_host_drift_events_total{host_id,hostname,direction}`: counter, incremented on
  ingest next to the fleet-wide `driftwatch_drift_events_total{direction}` (which stays), so
  `DriftwatchComplianceRegression` can name the host. In-process, so it resets when the API
  restarts; alert on `increase()`. All three directions are registered at 0 for every host
  (on enrollment and on each gauge refresh) before any increment, because `increase()`
  can't see the first increment of a series it never saw at 0. Removed from the registry
  on decommission.
- `driftwatch_hosts_decommissioned_total`: counter.

### Alerts (observability team)

Prometheus scrapes job `driftwatch-api` at `api:8080/actuator/prometheus` every 15 s.
Rule group `driftwatch`. Every alert has labels `severity` and annotations `summary`,
`description` and `runbook_url` (pointing into `deploy/observability/RUNBOOK.md` on GitHub).

| Alert | Fires when | Severity |
|---|---|---|
| `DriftwatchApiDown` | the API target is down for 1m | critical |
| `DriftwatchHostNotReporting` | a host's last-seen is more than 10 minutes old, for 2m | warning |
| `DriftwatchCriticalControlFailing` | a host fails at least one critical check, for 5m | critical |
| `DriftwatchHostComplianceLow` | a host's score is below 70, for 15m | warning |
| `DriftwatchComplianceRegression` | a host had a REGRESSED drift event in the last 10m (one alert per host) | info |

Each alert needs `promtool test rules` cases proving it fires and doesn't fire. The demo
stack must visibly fire at least `DriftwatchCriticalControlFailing` (debian-legacy) and
`DriftwatchComplianceRegression` (alpine-drifting). Alertmanager routes by severity to a
webhook receiver that logs alerts, so they're observable locally. Grafana gets a
provisioned Prometheus datasource and a "Driftwatch fleet compliance" dashboard. The
observability stack lives in `deploy/observability/compose.yml` under the compose profile
`observability`, which the root `docker-compose.yml` `include:`s:
`docker compose --profile observability up`.

| Variable | Default | Meaning |
|---|---|---|
| `GRAFANA_ADMIN_USER` | `admin` | Grafana admin login (observability profile only) |
| `GRAFANA_ADMIN_PASSWORD` | `driftwatch-demo` | Grafana admin password; demo value for the localhost-bound stack, override it anywhere else |

### Kubernetes (k3d + Helm)

- Two charts:
  - `deploy/helm/driftwatch`, the control plane: api, web, Keycloak in dev mode
    (`keycloak.enabled`), Postgres StatefulSet (`postgresql.enabled`, else an external DB
    via values and an existing Secret).
  - `deploy/helm/driftwatch-agent`, the node agent DaemonSet, installed into its own
    namespace because it needs a read-only hostPath mount of `/`.
- The control-plane namespace enforces Pod Security `restricted`: non-root, seccomp
  `RuntimeDefault`, read-only root FS (with `emptyDir` where writes are needed), all
  capabilities dropped, no privilege escalation. Every container has requests, limits and
  probes. NetworkPolicies are default-deny with explicit allows. The api has a
  PodDisruptionBudget. Secrets (enrollment token, DB password) come from values or an
  existing Secret, never hard-coded in templates. Optional ServiceMonitor, gated on the CRD
  existing.
- The agent DaemonSet mounts only what the checks read, read-only: the node's `/etc`,
  `/usr/lib` (the `os-release` symlink target; `mountUsrLib: false` for node images
  without one, as in k3d) and `/proc`, under `/host`, with
  `DRIFTWATCH_ROOT=/host`, `DRIFTWATCH_PROC=/host/proc`, and state in hostPath
  `/var/lib/driftwatch` (identity survives pod restarts). Not mounting `/` keeps kubelet's
  pod volumes (other pods' Secrets) out of view. The container runs as root with every
  capability dropped except `DAC_READ_SEARCH` (`/etc/shadow` is mode 0000 on RHEL-family
  systems), so its namespace is Pod Security `privileged` (hostPath requires it). The
  hostname comes from `/host/etc/hostname`, i.e. the node name. Its server URL is the
  in-cluster API service.
- `hostNetwork` defaults to `false`, so NetworkPolicies apply to the agent. The trade-off:
  network sysctls (`net.ipv4.*`) are per network namespace, so the four `network.*`
  checks then report the pod's namespace, not the node's. `hostNetwork: true` reports
  the node's values, and the control plane must then admit node addresses
  (`networkPolicy.api.extraIngress`).
- Images: `ghcr.io/tahausama210/driftwatch-{api,web,agent}`. Locally they're built as
  `:dev` and imported with `k3d image import`.
- Values the Terraform control plane relies on: `publicUrl` (sets the issuer and redirect
  base), `enrollment.existingSecret`, and an empty `ingress.host` meaning "match any
  host". The chart's NetworkPolicies must admit traffic that enters through the k3s
  Traefik ingress from outside the cluster.
- Local cluster: k3d cluster `driftwatch` (1 server + 2 agent nodes), pinned
  `rancher/k3s` image (at least two weeks old), load balancer `127.0.0.1:38080:80`, API
  `127.0.0.1:36443`. The issuer in k3d is `http://localhost:38080/auth/realms/driftwatch`.
- Commands: `helm lint` and `helm unittest` (pinned plugin) for both charts;
  `helm template … | kubeconform -strict -summary` against Kubernetes 1.36.6 schemas, all
  run by `scripts/check-helm.sh` with digest-pinned images (Helm 4.3.0, helm-unittest
  1.2.1, kubeconform 0.8.0);
  `deploy/k3d/up.sh` (create cluster, build and import images, install both charts,
  wait), `deploy/k3d/smoke.sh` (readiness through the ingress, viewer token, one enrolled
  host per node with a scan, dashboard served), `deploy/k3d/down.sh`.

### Terraform (AWS test fleet)

- `infra/terraform/` contains `modules/{network,ssm-instance-role,control-plane,fleet}`
  (both instance modules share `ssm-instance-role`), a root at `envs/demo`, and
  `bootstrap/` (S3 state bucket: versioned, encrypted, public access blocked; native S3
  locking with `use_lockfile`). Terraform `>= 1.10` (`>= 1.11` for `envs/demo`, whose
  enrollment token is an ephemeral value written to SSM write-only, so it never enters
  state), AWS provider `~> 6.70`. Commit `.terraform.lock.hcl` with hashes for
  linux_amd64, darwin_arm64 and windows_amd64.
- **Never run `plan` or `apply` against AWS.** There are no credentials, and the user
  chose no real deploy. Verification is `terraform fmt -check -recursive`,
  `terraform init -backend=false`, `terraform validate`, and `terraform test` with
  `mock_provider "aws"` in every module. Also `tflint --recursive` with the pinned AWS
  ruleset, and `trivy config infra/terraform` with no HIGH or CRITICAL findings unless an
  ignore says why.
- Security requirements, each asserted by a test:
  - no SSH: no port 22 ingress and no key pairs; access is through SSM Session Manager
  - IMDSv2 required
  - EBS encrypted
  - the instance profile has only what SSM needs, plus read access to the one
    enrollment-token parameter
  - no `0.0.0.0/0` ingress anywhere
  - the control plane accepts the API port only from the fleet security group and an
    `admin_cidrs` variable (empty by default)
- The control plane is one EC2 instance running k3s via cloud-init, installing the chart
  from `oci://ghcr.io/tahausama210/charts/driftwatch` at a version variable.
- The fleet is N instances spread across Ubuntu 24.04, Debian 12 and Amazon Linux 2023.
  AMIs come from the public SSM parameters (no marketplace subscriptions). Cloud-init:
  - download `driftwatch-agent_<version>_linux_<arch>.tar.gz` from the GitHub release
  - verify it against `checksums.txt`
  - read the enrollment token from an SSM SecureString created by Terraform
  - run the agent as a hardened systemd unit. Unlike the container image (root, read-only
    host mount), it runs as a transient non-root user with only `CAP_DAC_READ_SEARCH`,
    which is enough to read `/etc/shadow` and the other checked files.
- Public subnets with no inbound access and no NAT gateway (cost). A NAT gateway or VPC
  endpoints are the documented production alternative. HTTP inside the VPC is a
  documented tradeoff.
- `infra/terraform/README.md` covers usage, what each test asserts, and how to apply and
  destroy with your own account. Give no cost figures beyond what can be cited.

### Supply chain and CI (platform team)

- Release artifacts (on a `v*` tag; agents never create tags):
  - `driftwatch-agent_<version>_linux_{amd64,arm64}.tar.gz`, each containing exactly one
    `driftwatch-agent` binary plus `LICENSE` and `README.md` (the Terraform installer
    refuses an archive with zero or several binaries)
  - `checksums.txt` (sha256, `<sha>  <file>` lines) and an SPDX SBOM, attached to the
    GitHub Release. Every archive gets a Sigstore-backed GitHub build-provenance and SBOM
    attestation (`gh attestation verify <archive> --repo TahaUsama210/driftwatch`).
  - The tag is `v<version>` (e.g. `v0.2.0`), so files are at
    `https://github.com/TahaUsama210/driftwatch/releases/download/v<version>/<file>`.
  - images `ghcr.io/tahausama210/driftwatch-{api,web,agent}:<version>`
  - both charts pushed to `oci://ghcr.io/tahausama210/charts`
- On push to `main`: images tagged `main` and `sha-<short>`. Images are multi-arch
  (linux/amd64, linux/arm64). Build stages use `FROM --platform=$BUILDPLATFORM` and
  cross-compile or produce arch-independent output (Go via `TARGETARCH`, JVM jar, static
  web assets); the api and web teams update their own Dockerfiles, and the platform team
  updates `agent/Dockerfile`.
- Every image gets:
  - a Trivy scan (fail on fixable HIGH/CRITICAL; SARIF uploaded to code scanning)
  - an SBOM and build provenance attestations
  - a cosign keyless signature by digest
- `trivy config` scans `infra/terraform`, `deploy/helm` and the Dockerfiles.
- New CI jobs:
  - terraform: fmt, validate, test, tflint, trivy config
  - helm: lint, unittest, kubeconform
  - observability: promtool and amtool checks, rule tests, dashboard JSON validity
  - kubernetes e2e: k3d up + smoke
  - the compose e2e, updated for auth
- Each job gets least-privilege `permissions` (`packages: write`, `id-token: write` and
  `security-events: write` only where needed).
- `scripts/smoke.sh` gains auth checks, with tokens from
  `${WEB_URL}/auth/realms/driftwatch/protocol/openid-connect/token` via client
  credentials:
  - an unauthenticated `GET /api/v1/hosts` is `401`
  - viewer gets `200`
  - viewer `DELETE` is `403`
  - admin `DELETE` of a throwaway host the smoke test enrolled itself is `204`, after
    which that host's agent token gets `401`
  - the demo hosts are never deleted
