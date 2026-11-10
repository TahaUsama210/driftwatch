# Driftwatch API

Spring Boot 4.1 / Java 25 service that enrolls hosts, ingests their compliance scans, scores them,
records drift between consecutive scans, and serves the read models behind the dashboard.

The HTTP contract is [`../contracts/openapi.yaml`](../contracts/openapi.yaml); domain rules (scoring,
drift directions, limits) are in [`../docs/CONTRACTS.md`](../docs/CONTRACTS.md). Every response in the
integration test suite is validated against the OpenAPI file, so the build fails when the code and the
contract disagree.

## Architecture

```
agent ──POST /agents/enroll──▶ ┌──────────────────────── driftwatch-api ───────────────────────┐
      ──POST /scans─────────▶  │ filters: request id → ingest outcome metric → 1 MiB limit      │
                               │ Spring Security: agent chain (enrollment / agent token),      │
dashboard ──/api/v1/** + JWT─▶ │   dashboard chain (Keycloak JWT, viewer/admin), public chain  │
                               │ controllers → services (ingest, drift, fleet) → JdbcClient SQL │──▶ Postgres 18
Prometheus ──/actuator/──────▶ │ Actuator: liveness, readiness (incl. DB), Prometheus metrics  │    (Flyway)
                               └───────────────────────────────────────────────────────────────┘
```

Code is organised by feature under `io.github.tahausama210.driftwatch`:

| Package | Responsibility |
|---|---|
| `enrollment` | `POST /api/v1/agents/enroll`: upsert host by machine id, issue and rotate agent tokens |
| `ingest` | `POST /api/v1/scans`: validation, idempotency, scoring, the ingest transaction, ingest metrics |
| `drift` | drift direction rules, event persistence, `GET /hosts/{id}/drift`, `GET /events`, per-host drift counters |
| `hosts` | host list (filter, sort, page), host detail, scan history, ONLINE/STALE policy, decommission |
| `scans` | scan summary/detail read models |
| `checks` | check catalog upsert and per-check fleet status |
| `fleet` | fleet summary, score trend, scheduled fleet and per-host gauges |
| `security` | the three filter chains, agent tokens, dashboard JWT validation and role mapping, CORS |
| `common` | RFC 9457 error handling, request id and body-limit filters, JDBC helpers |
| `config` | typed, validated configuration; clock; OpenAPI metadata; filter ordering |

There are no package cycles: `common` and `config` depend on nothing else, and the features depend on
each other in one direction only (`checks` ← `scans` ← `hosts` ← `drift` ← `ingest`; `fleet` reads
`checks`, `hosts` and `drift`; `enrollment` uses `hosts`, `drift` and `security`; `hosts` uses
`security` only for the audit identity). A decommission tells the in-memory per-host series about
itself through a `HostDecommissioned` application event, so `hosts` does not depend on `drift`.

## Endpoints

| Method and path | Auth | Purpose |
|---|---|---|
| `POST /api/v1/agents/enroll` | enrollment token | `201` new host, `200` re-enrolled (token rotated) |
| `POST /api/v1/scans` | agent token | `201` stored, `200` duplicate `scanId`, `409` scan id owned by another host, `413` body over 1 MiB |
| `GET /api/v1/fleet/summary` | JWT, `viewer` | host counts, average score, critical failures, top 5 failing checks |
| `GET /api/v1/fleet/trend?hours&bucketMinutes` | JWT, `viewer` | carried-forward average per epoch-aligned bucket, at most 500 points |
| `GET /api/v1/hosts?query&status&sort&direction&page&size` | JWT, `viewer` | paged host list with latest scan |
| `GET /api/v1/hosts/{hostId}` | JWT, `viewer` | one host with latest scan |
| `DELETE /api/v1/hosts/{hostId}` | JWT, `admin` | `204` host and its history deleted, agent token revoked; `404` unknown host |
| `GET /api/v1/hosts/{hostId}/scans?limit` | JWT, `viewer` | scan history, newest first |
| `GET /api/v1/hosts/{hostId}/drift?from&to` | JWT, `viewer` | changes between two scans (defaults: latest vs. previous) |
| `GET /api/v1/scans/{scanId}` | JWT, `viewer` | scan with all results, by category then check id |
| `GET /api/v1/checks`, `GET /api/v1/checks/{checkId}` | JWT, `viewer` | catalog with status counts across latest scans; per-host status |
| `GET /api/v1/events?hostId&direction&limit` | JWT, `viewer` | drift event feed, newest first |
| `GET /actuator/health/liveness`, `/readiness`, `/actuator/prometheus` | public | probes and metrics |
| `GET /v3/api-docs`, `/swagger-ui.html` | public | OpenAPI generated from the running code |

Everything else is denied. A missing token is `401` with `WWW-Authenticate: Bearer`; a rejected one
(expired, wrong issuer or audience, bad signature, not a JWT) adds `error="invalid_token"` (RFC 6750).
A valid token without the role is `403`. All errors are `application/problem+json` (RFC 9457) with `type`, `title`,
`status`, `detail`, `instance`, and for validation failures an `errors: [{field, message}]` array whose
`field` is a path such as `results[3].checkId` or a query parameter name. Malformed UUIDs in a *path*
are `404` (they cannot name a resource); malformed query parameters are `400`, as are URLs Spring
Security's firewall rejects (e.g. containing `//` or `;`).

## Data model

Defined in [`V1__create_schema.sql`](src/main/resources/db/migration/V1__create_schema.sql), which
explains each non-obvious choice inline.

- **hosts**: identity (`machine_id` unique), SHA-256 `token_hash` (unique, used to authenticate),
  current facts, `enrolled_at`, `last_seen_at`, and `latest_scan_id`, a denormalised pointer to the
  newest scan.
- **scans**: agent-generated id (the idempotency key), timestamps, status counts, and the score as
  `numeric(4,1)`, `NULL` when nothing was applicable. Indexed on `(host_id, finished_at DESC, id DESC)`.
- **scan_results**: one row per check per scan, primary key `(scan_id, check_id)`.
- **checks**: catalog upserted from reports; title, category and severity of each check.
- **drift_events**: one row per changed check per scan, unique on `(scan_id, check_id)`, indexed for
  the fleet feed and the per-host feed by `detected_at DESC`.

`V2` indexes `drift_events.previous_scan_id`: deleting a scan makes Postgres check every foreign key
that references it, and that was the one without an index (the migration explains why
`hosts.latest_scan_id` stays unindexed).

Statuses, severities, categories and directions are `text` with `CHECK` constraints, and length limits
mirror the contract's validation limits. Generated ids use Postgres 18's built-in `uuidv7()`.

## Key decisions and tradeoffs

**`JdbcClient`, not JPA.** Almost every read is an aggregate over "each host's latest scan": counts per
status per check, averages, a carry-forward trend built with `generate_series` and `LATERAL` joins,
`FILTER` clauses, row-value comparisons for "previous scan". Those are natural in SQL and awkward in
JPQL or the Criteria API, and an entity graph would add lazy-loading and N+1 risks without giving
anything back: there are no rich aggregates to load, mutate and flush. The write path also leans on
Postgres features directly (`SELECT … FOR UPDATE`, `INSERT … ON CONFLICT`, multi-row inserts via
`unnest` arrays). The cost is hand-written row mapping, which is kept in one place per read model
(`ScanSummaryMapping`, `HostQueries.mapHost`, …). Queries that run more than one statement use a
read-only `REPEATABLE READ` transaction so their numbers come from one snapshot (e.g. the host page and
its `totalItems`).

**Denormalised latest-scan pointer.** `hosts.latest_scan_id` turns "latest scan per host" into a
primary-key join instead of a `DISTINCT ON` or window function over all scans. The cost is that ingest
must keep it correct: it is only moved by a scan that is newer, by `(finished_at, id)`, than every
stored scan of the host, and it is updated in the same transaction, under the host row lock.

**Row lock for drift consistency.** Ingest starts with `SELECT … FROM hosts … FOR UPDATE`. Concurrent
submissions for one host are therefore serialised, so each one computes drift against a previous scan
that cannot change before it commits, and two scans can never both believe they are the newest. Scans
of different hosts lock different rows and do not contend. The lock query also re-checks the token
hash, which closes the window where a concurrent re-enrollment rotates the token after the request was
authenticated. The catalog upsert writes only new or changed check definitions, in id order, so
concurrent ingests do not queue on (or deadlock over) shared catalog rows.

**Out-of-order scans.** A scan can arrive after a newer one (an agent's retry landing after its next
interval's scan). It is stored and scored normally, its events are computed against its predecessor by
`finishedAt`, and the events of its successor are deleted and re-derived against it. The invariant is
`events(S) = diff(previous(S), S)` for every scan. A late scan does not move the latest-scan pointer or
overwrite host facts, which would be older than what is stored, but it does update `lastSeenAt`. The
drift-events counter counts only the submitted scan's events, not the re-derived replacements.

**Idempotency.** The scan id is the primary key. Under the host lock, a scan id already stored for the
same host returns `200` with the stored summary and writes nothing; one stored for another host is
`409`. If another host inserts the same id concurrently, the primary-key violation is mapped to `409`.

**Token handling.** Agent tokens are `dwa_` plus 32 bytes from `SecureRandom`, base64url without
padding. Only the SHA-256 hex digest is stored; the token is returned once. An unsalted fast hash is
appropriate here, unlike for passwords, because the input already has 256 bits of entropy. Agent
tokens are looked up by digest through the unique index. The enrollment secret is compared with
`MessageDigest.isEqual` on SHA-256 digests of both values, which is constant-time and hides the
secret's length. Re-enrollment replaces the digest, so the previous token stops working when the
transaction commits. Tokens are never logged, and the configuration and response records redact them
in `toString()`. A missing or short enrollment secret fails startup without printing the value.

**Three filter chains, one credential each.** The agent chain matches only `POST /agents/enroll` and
`POST /scans` and never decodes a JWT; the dashboard chain (`/api/**`) is an OAuth2 resource server
and never looks up agent tokens; the last chain serves probes, metrics and the OpenAPI document and
denies the rest. A credential presented at the wrong chain therefore fails like any unknown token
(`401`), which the tests check in both directions. CSRF protection is disabled because the API keeps
no session and accepts credentials only in an explicit `Authorization` header, which a cross-site page
cannot make a browser attach. CORS allows `GET` and `DELETE` with an `Authorization` header from
`DRIFTWATCH_CORS_ORIGINS` only, and is off on the agent chain.

**Dashboard tokens.** Access tokens from the `driftwatch` Keycloak realm are validated locally:
RS256 signature against the realm's JWKS, `typ` absent or `JWT`, a required `exp` (60 s skew, read
from the application clock), the exact issuer `DRIFTWATCH_OIDC_ISSUER`, an `aud` containing
`DRIFTWATCH_OIDC_AUDIENCE`, and a `sub`. Pinning RS256 also rules out `alg: none` and HMAC
algorithm-confusion tokens (tested). Roles come only from `realm_access.roles` and become
`ROLE_viewer` / `ROLE_admin`; scopes and client roles grant nothing. A role hierarchy makes `admin`
imply `viewer`, mirroring the realm's composite role, so the API does not rely on Keycloak expanding
composites into the token. A rejected token is logged at INFO with the validator's reason (e.g. "The
iss claim is not valid"), never the token: a wrong issuer is the likeliest deployment mistake, and
the client only sees a generic `401`.

**Lazy, cached signing keys.** Nothing contacts Keycloak at startup, so the API starts and reports
ready while it is down (readiness checks only the database; tested). The JWKS is fetched on the first
token, cached by Nimbus for five minutes, and refetched early when a token names an unknown key id,
which is how a key rotation is picked up. While the JWKS endpoint is unreachable and nothing is
cached, a token cannot be checked and the response is `500`, not `401` (a valid token must not be
reported as invalid, which would send the dashboard into a login loop); the API recovers without a
restart once Keycloak returns (tested). JWKS fetches use 2 s connect and 5 s read timeouts instead
of Spring's 30 s defaults, because they are serialised behind one lock.

**Decommission.** `DELETE /api/v1/hosts/{hostId}` deletes the host, its scans, results and drift
events in one transaction, child-first: the schema deliberately has no `ON DELETE CASCADE`, so
history is only ever removed by an explicit statement. The agent token's hash lives on the host row,
so the token stops working at commit. It takes the same host row lock as ingest, which serialises the
two: an ingest already holding the lock commits its scan and the decommission then deletes it too; an
ingest queued behind a decommission finds the row gone and gets `401`, writing nothing (both orders
are tested). A re-enrollment racing a decommission (its insert saw the row, its update found it
deleted) starts over and enrolls the machine as a new host instead of failing. After commit, the
service increments `driftwatch_hosts_decommissioned_total` and writes a structured audit line on the
`io.github.tahausama210.driftwatch.audit` logger with the host, the deleted row counts, and the
actor's `sub` and `preferred_username`.

**Request body limit.** A filter rejects a declared `Content-Length` over 1 MiB before reading, and
counts bytes for chunked bodies, failing the read once the limit is crossed. Both paths return `413`.

## Observability

- Structured JSON logs (Boot's ECS format) on stdout. Every line logged during a request carries
  `requestId`, taken from a safe `X-Request-Id` header or generated, and echoed in the response.
- Metrics at `/actuator/prometheus`: `driftwatch_scans_ingested_total{outcome}` (created, duplicate,
  rejected), `driftwatch_scan_ingest_duration_seconds` (histogram), `driftwatch_drift_events_total{direction}`,
  `driftwatch_hosts{status}`, `driftwatch_fleet_average_score`, `driftwatch_hosts_decommissioned_total`,
  plus Boot's HTTP server metrics. The gauges are recomputed every `driftwatch.metrics-refresh-interval`
  (15 s) by scheduled tasks, so scrapes never query the database.
- Per-host series, labelled `host_id` (identity) and `hostname` (for people):
  `driftwatch_host_score` (absent while the latest score is null), `driftwatch_host_last_seen_timestamp_seconds`,
  `driftwatch_host_failing_checks{severity}` (FAIL + ERROR on the latest scan, all four severities,
  absent until the first scan) and the counter `driftwatch_host_drift_events_total{direction}`. A
  persisting gauge series is updated in place, never removed and re-registered, so a scrape that
  lands mid-refresh cannot reset an alert's `for:` timer. Every host's three drift series are
  registered at zero on enrollment and on every refresh (which also covers a restart), because
  `increase()` cannot see the first increment of a series it never saw at zero. A decommissioned
  host's drift series are removed at once and its gauges on the next refresh; the refresh also drops
  series of renamed hosts.
- **Cardinality**: at most 9 series per host (1 score, 1 last-seen, 4 failing-checks, 3 drift), so a
  fleet of 500 hosts adds at most 4,500 series. That is comfortable for one Prometheus; tens of
  thousands of hosts would call for dropping `hostname` or moving per-host data out of Prometheus.
- `/actuator/health/liveness` (process only) and `/actuator/health/readiness` (includes the database).

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:35433/driftwatch` | JDBC URL |
| `DB_USER` / `DB_PASSWORD` | `driftwatch` / `driftwatch` | database credentials |
| `DRIFTWATCH_ENROLLMENT_TOKEN` | none | required, at least 24 characters |
| `DRIFTWATCH_STALE_AFTER` | `PT5M` | a host is `STALE` when its last contact is older |
| `DRIFTWATCH_CORS_ORIGINS` | empty | comma-separated browser origins |
| `DRIFTWATCH_OIDC_ISSUER` | none | required: the exact `iss` to accept, e.g. `http://localhost:8400/auth/realms/driftwatch`; startup fails without it |
| `DRIFTWATCH_OIDC_JWKS_URI` | `${DRIFTWATCH_OIDC_ISSUER}/protocol/openid-connect/certs` | where signing keys are fetched; compose and Kubernetes point it at the internal Keycloak service |
| `DRIFTWATCH_OIDC_AUDIENCE` | `driftwatch-api` | a value `aud` must contain |
| `SERVER_PORT` | `8080` | HTTP port |

## Running locally

Start a private database (port 35433, so it never clashes with anything on 5432):

```sh
docker run -d --name driftwatch-dev-db -p 35433:5432 \
  -e POSTGRES_USER=driftwatch -e POSTGRES_PASSWORD=driftwatch -e POSTGRES_DB=driftwatch \
  postgres:18-alpine
```

Run the API on port 8480 (Flyway creates the schema on startup):

```sh
# Linux/macOS/Git Bash
DRIFTWATCH_ENROLLMENT_TOKEN=dev-enrollment-token-0123456789abcdef \
DRIFTWATCH_OIDC_ISSUER=http://localhost:8400/auth/realms/driftwatch SERVER_PORT=8480 ./mvnw spring-boot:run
```

```powershell
# PowerShell
$env:DRIFTWATCH_ENROLLMENT_TOKEN = 'dev-enrollment-token-0123456789abcdef'; $env:DRIFTWATCH_OIDC_ISSUER = 'http://localhost:8400/auth/realms/driftwatch'; $env:SERVER_PORT = '8480'; .\mvnw.cmd spring-boot:run
```

The read endpoints need a token from the compose Keycloak (`docker compose up`), e.g. the smoke
viewer's: `curl -s -d grant_type=client_credentials -d client_id=driftwatch-smoke-viewer -d
client_secret=smoke-viewer-demo-secret http://localhost:8400/auth/realms/driftwatch/protocol/openid-connect/token`
(demo secret, localhost only). Then open `http://localhost:8480/swagger-ui.html` and paste the
`access_token`, or enroll from the shell:

```sh
curl -s -X POST localhost:8480/api/v1/agents/enroll \
  -H 'Authorization: Bearer dev-enrollment-token-0123456789abcdef' -H 'Content-Type: application/json' \
  -d '{"machineId":"dev-machine-01","agentVersion":"0.1.0","hostname":"dev-01","os":{"id":"ubuntu","version":"24.04","prettyName":"Ubuntu 24.04 LTS"},"kernel":"6.8.0","arch":"amd64"}'
```

## Tests and build

```sh
./mvnw -B verify      # Windows: .\mvnw.cmd -B verify
```

This is the single CI command. It compiles with `-Xlint:all -Werror`, runs the unit tests (Surefire,
`*Test`) and the integration tests (Failsafe, `*IT`), writes a merged JaCoCo report to
`target/site/jacoco/jacoco.xml`, and checks formatting (Spotless with palantir-java-format; run
`./mvnw spotless:apply` to fix). The integration tests need Docker: Testcontainers starts
`postgres:18.4-alpine` on a random port, shared by every `*IT` class. Almost all of them also share
one application context, whose dashboard tokens are signed by `support/TestIdentityProvider`: an RSA
key whose JWKS is served over real HTTP on a random port, so the API fetches and caches keys exactly
as it does from Keycloak. Two tests need their own context: `IdentityProviderOutageIT` (JWKS
unreachable at startup) and `KeycloakIT`, which runs `quay.io/keycloak/keycloak:26.8.1` with the
realm file from `../deploy/helm/driftwatch/files/keycloak/` and authorises the smoke clients'
client-credentials tokens (about 30 s, most of it Keycloak starting).

- **Unit tests** cover the score formula with worked examples (including half-up rounding cases where
  half-even would differ), the full drift direction table, token generation and hashing, bearer
  parsing and the enrollment comparison, configuration fail-fast, trend bucket alignment and the
  500-point cap, cross-field scan validation, the body-limit filter, and problem rendering for paths
  the running app cannot easily reach (`403`, `500`).
- **Integration tests** drive every endpoint through MockMvc against a real Postgres: auth failures,
  enrollment and token rotation, created/duplicate/conflict ingest, validation errors with field
  paths, `413` (declared length, and chunked over real HTTP), drift across consecutive and
  out-of-order scans, fleet summary and trend numbers, `STALE` via a controllable `Clock`, paging,
  sorting and filtering, CORS, request ids, probes and metrics.
- **Contract test**: `support/OpenApiContract` validates every integration-test response (status,
  media type, body schema) against `../contracts/openapi.yaml` using Atlassian's
  `openapi-request-validator-core`. `OpenApiContractTest` proves the check fails on violations. One
  narrowly scoped exception is documented in that class: the validator's strict
  `additionalProperties` handling misfires on `allOf` schemas, so for the three `allOf` responses
  (`ScanDetail`, `CheckDetail`, `DriftEvent`) undocumented properties are caught instead by tests
  that assert their exact property names.
- **Concurrency tests** make the races the host row lock guards against deterministic: a token
  rotated by a re-enrollment while a submission waits for the lock (`401`), the same scan id
  committed by another host mid-ingest (`409`), a decommission behind an in-flight ingest and an
  ingest behind a decommission, and a re-enrollment racing a decommission.
- **Authentication tests** sign real JWTs: viewer and admin access, `403` without a role, and `401`
  for a wrong issuer or audience, no audience, expiry beyond the skew, no `exp`, no `sub`, an unknown
  key, an HS256 token keyed with the public key, malformed tokens, agent tokens on dashboard
  endpoints and dashboard tokens on agent endpoints.

## Docker

```sh
docker build -t driftwatch-api:dev api     # from the repository root
```

Multi-stage. The build stage is `FROM --platform=$BUILDPLATFORM`: a jar is the same bytecode for every
target, so a multi-arch build (`linux/amd64,linux/arm64`) runs Maven once, natively, never under QEMU;
only the runtime stage is per target. The Maven Wrapper builds the jar with a BuildKit cache mount for `~/.m2` (tests are
skipped; CI runs them), Boot's jar tools split it into dependency, loader, snapshot and application
layers, and the runtime stage is `eclipse-temurin:25.0.4_7-jre-alpine`. The process runs as UID 10001,
needs no Linux capabilities, listens on 8080, sizes the heap with `-XX:MaxRAMPercentage=75`, and has a
`HEALTHCHECK` using busybox `wget` against the readiness probe.
