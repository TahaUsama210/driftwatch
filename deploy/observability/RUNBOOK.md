# Driftwatch alert runbook

One section per alert in [`prometheus/rules/driftwatch.yml`](prometheus/rules/driftwatch.yml).
Each alert's `runbook_url` annotation links to its section here.

Where to look, in the local compose stack:

| Tool | URL |
|---|---|
| Grafana, "Driftwatch fleet compliance" dashboard | http://localhost:33000/d/driftwatch-fleet |
| Prometheus alerts and targets | http://localhost:39090/alerts, http://localhost:39090/targets |
| Alertmanager (routing, silences) | http://localhost:39093 |
| Driftwatch dashboard (host detail, drift events) | http://localhost:8400 |
| API (needs a user bearer token from Keycloak) | http://localhost:8480/api/v1/... |

Routing: `critical` alerts notify after 10 s and repeat hourly, `warning` after 30 s
and every 4 h, `info` after 30 s and every 12 h. Alerts are grouped by `alertname`, so one
notification lists every affected host. While `DriftwatchApiDown` fires it inhibits every
other Driftwatch alert, because the per-host series stop being scraped.

---

## DriftwatchApiDown

**Severity:** critical. **Fires when** `up{job="driftwatch-api"}` has been 0 for 1 minute,
or the job is missing from the scrape config altogether (`absent()`).

**What it means.** Prometheus cannot scrape `api:8080/actuator/prometheus`. Either the API
is down, or it can't be reached from Prometheus. Agents can't submit scans while the API is
down, and every other Driftwatch alert loses its data and stays inhibited.

**Likely causes**

- The API container exited or keeps restarting: a bad `DRIFTWATCH_ENROLLMENT_TOKEN` (fewer than
  24 characters) stops startup, and so does an unreachable database while Flyway runs.
- The API process is up but stuck or out of memory, so scrapes time out.
- Network or config: the observability profile was started without the API (for example
  this compose file run standalone), or the scrape target or `metrics_path` changed.

**Investigate**

1. On http://localhost:39090/targets, read the `driftwatch-api` target's error. A DNS
   `lookup api` failure means there is no `api` service on the network. `connection refused`
   means the container is up but the process isn't listening. A 404 means the path changed.
2. `docker compose ps api db` and `docker compose logs --tail=100 api`. The API logs ECS JSON.
   Look for startup failures and Hikari connection errors.
3. `curl -s http://localhost:8480/actuator/health/readiness`. `DOWN` with the `db` component
   down points at Postgres.

**Resolve.** Fix the cause, then `docker compose up -d api`. The alert resolves on the first
successful scrape. If the API is down on purpose (maintenance), add a silence in Alertmanager
on `alertname="DriftwatchApiDown"` for the window instead of letting it page.

---

## DriftwatchHostNotReporting

**Severity:** warning. **Fires when** `time() - driftwatch_host_last_seen_timestamp_seconds > 600`
holds for 2 minutes, i.e. a host has been silent for more than 12 minutes in total.

**What it means.** The host stopped contacting the API, so its score and failing checks are
stale. The API already marks it `stale` after `DRIFTWATCH_STALE_AFTER` (5 minutes by
default). This alert waits longer, so a missed check-in or a restart doesn't page anyone.

**Likely causes**

- The host or its agent is stopped (`docker compose stop host-...`, a crashed agent, a host
  that was powered off).
- The agent can't reach or authenticate to the API: network change, rotated enrollment
  token, a host token that is no longer valid (`401` in the agent log).
- The host was retired without being decommissioned in Driftwatch.

**Investigate**

1. In Grafana, "Hosts stale" shows how many hosts are affected. Pick the host in the `Host`
   variable to see when its score series stopped changing.
2. Open the host in the Driftwatch dashboard to see its last contact time and latest scan.
3. For a compose demo host: `docker compose ps host-<name>` and
   `docker compose logs --tail=50 host-<name>`. The agent logs each failed submission.

**Resolve.** Restart the agent or fix its connectivity and credentials. The alert resolves
on the first evaluation after the host checks in. If the host is gone for good, decommission
it (`DELETE /api/v1/hosts/{hostId}` or the dashboard). The API drops its series on the next
15 s metrics refresh, and the alert resolves.

---

## DriftwatchCriticalControlFailing

**Severity:** critical. **Fires when** `driftwatch_host_failing_checks{severity="critical"} > 0`
for 5 minutes. It keeps firing for 2 minutes after the condition clears, so one missed
scrape doesn't resolve it and re-arm it.

**What it means.** The host's latest scan has at least one critical-severity check in
`FAIL` or `ERROR`. The critical checks are `ssh.permit_empty_passwords`,
`accounts.uid0_only_root` and `accounts.no_empty_passwords` (see the check catalog in
`docs/CONTRACTS.md`). `ERROR` counts as failing: a control that can't be verified isn't
compliant. The alert's value is the number of failing critical checks.

**Likely causes**

- An account other than `root` with UID 0 (this is what `debian-legacy` in the demo fleet
  has, on purpose).
- An account with an empty password field in `/etc/shadow`.
- `PermitEmptyPasswords yes` in `sshd_config` or an included drop-in.
- `ERROR` results: the agent couldn't read `/etc/shadow` or `/etc/passwd` (not running as
  root, file missing).

**Investigate**

1. Grafana, "Hosts failing critical checks" lists the hosts and counts.
2. Open the host in the Driftwatch dashboard, or `GET /api/v1/hosts/{hostId}`. The latest
   scan lists each check's status and evidence (for example the offending account name).
3. Check the host's drift (`GET /api/v1/hosts/{hostId}/drift`) to tell a fresh regression
   apart from a long-standing failure.

**Resolve.** Fix the control on the host: remove or re-number the extra UID-0 account, lock
or set a password for empty-password accounts, set `PermitEmptyPasswords no` and reload
sshd. The next scan reports `PASS`, and the alert resolves about 2 minutes later. Treat an
unexplained new UID-0 account as a possible compromise and escalate before you clean it up.
For the demo host, the failure is deliberate. Silence the alert in Alertmanager if it gets
in the way.

---

## DriftwatchHostComplianceLow

**Severity:** warning. **Fires when** `driftwatch_host_score < 70` for 15 minutes. It keeps
firing for 5 minutes after the score recovers, so a score hovering around 70 doesn't flap.

**What it means.** The host passes less than 70% of its applicable checks, weighted by
severity (`low` 1, `medium` 2, `high` 3, `critical` 5). Hosts with no applicable checks have
no score and never fire. Exactly 70 doesn't fire.

**Likely causes**

- A host built from an unhardened image or provisioned outside the baseline.
- Several medium or high checks failing at once after a config change (SSH config,
  `login.defs`, network sysctls).

**Investigate**

1. Grafana, "Score by host" shows when the score dropped and whether it was sudden (a change)
   or always low (a baseline problem). "Current score, lowest first" ranks the fleet.
2. "Failing checks by severity", filtered to the host, shows which severities make up the
   gap.
3. Open the host in the Driftwatch dashboard for the failing checks and their evidence.

**Resolve.** Fix the failing checks, high and critical first since they weigh most. The
alert resolves 5 minutes after the score reaches 70 or more. If the host is meant to stay
below the bar (a lab box), silence it by `host_id` with an expiry, rather than for good.

---

## DriftwatchComplianceRegression

**Severity:** info. **Fires when**
`sum by (host_id, hostname) (increase(driftwatch_host_drift_events_total{direction="REGRESSED"}[10m])) > 0`,
with no `for:`. That's one alert per host, firing until 10 minutes have passed with no
further regression on that host.

**What it means.** At least one check on the named host went from `PASS` or
`NOT_APPLICABLE` to `FAIL` or `ERROR` between two consecutive scans in the last 10 minutes.
The value is approximate (`increase()` extrapolates), so read it as "about N". The counter
lives in the API process, so it resets when the API restarts; `increase()` treats that as a
reset, not a regression.

**Likely causes**

- A configuration change rolled out to the host (intended or not).
- A host flapping between two states, as `alpine-drifting` does in the demo: it flips
  `PermitRootLogin` every couple of minutes, so its alert stays firing for the whole demo.
- A package upgrade that replaced a hardened config file with the vendor default.
- Many hosts at once: a fleet-wide rollout. Alerts are grouped by `alertname`, so they arrive
  as one notification that lists the hosts.

**Investigate**

1. Grafana, "Regressions by host" ranks hosts by regressions in the selected range, and
   "Drift events" shows regressions (up) against resolutions (down) over time. A regression
   matched by a resolution a few minutes later suggests flapping, not a lasting change.
2. The Driftwatch dashboard's drift feed, or
   `GET /api/v1/events?hostId=<host_id>&direction=REGRESSED`, gives the check and the before
   and after evidence.
3. If the host regresses again and again, compare its scans with
   `GET /api/v1/hosts/{hostId}/drift` and look for whatever keeps rewriting the config
   (config management fighting a manual edit, a cron job).

**Resolve.** No action is needed if the change was intended and its checks are now tracked
by the other alerts. Otherwise revert the change on the host. A regressed critical check also
raises `DriftwatchCriticalControlFailing` after 5 minutes, and a low score raises
`DriftwatchHostComplianceLow`, so this alert is the early heads-up, not the page. To quiet a
host that flaps on purpose (like the demo host), silence on its `host_id`.
