# Observability: Prometheus, Alertmanager, Grafana

Metrics, alerting and dashboards for Driftwatch. Prometheus scrapes the API, evaluates five
alert rules, and sends notifications through Alertmanager to a webhook sink that logs them.
Grafana shows the "Driftwatch fleet compliance" dashboard.

```
api:8080/actuator/prometheus ──scrape 15s──> prometheus ──alerts──> alertmanager ──webhook──> alert-sink (logs)
                                                 ^
                                              grafana
```

| Service | Image | Host URL |
|---|---|---|
| `prometheus` | `prom/prometheus:v3.15.1` | http://localhost:39090 |
| `alertmanager` | `prom/alertmanager:v0.34.1` | http://localhost:39093 |
| `grafana` | `grafana/grafana:13.2.4` | http://localhost:33000 |
| `alert-sink` | `mendhak/http-https-echo:42` | none (internal, `alert-sink:8080`) |

Ports are bound to 127.0.0.1. Each container runs as its image's non-root user, with a
read-only root filesystem, no Linux capabilities, `no-new-privileges`, and state in named
volumes. Config files are mounted read-only.

## Running it

From the repository root (the root `docker-compose.yml` `include:`s
[`compose.yml`](compose.yml)):

```sh
docker compose --profile observability up -d --build --wait
docker compose logs -f alert-sink          # notifications, one JSON line per webhook call
docker compose --profile observability down -v
```

Without `--profile observability` none of these services start.

Grafana: sign in at http://localhost:33000 as `admin` / `driftwatch-demo`. Set
`GRAFANA_ADMIN_USER` and `GRAFANA_ADMIN_PASSWORD` in `.env` for anything other people can
reach. Anonymous access and sign-up are off. The dashboard is the home page, and it is also
under Dashboards > Driftwatch. It is provisioned from
[`grafana/dashboards/driftwatch-fleet.json`](grafana/dashboards/driftwatch-fleet.json) and
can't be edited in the UI: change the JSON instead. Grafana uses the plugins bundled in its
pinned image and doesn't download plugin updates at startup.

## Alerts

Defined in [`prometheus/rules/driftwatch.yml`](prometheus/rules/driftwatch.yml), rule group
`driftwatch`. [`RUNBOOK.md`](RUNBOOK.md) has a section for each one.

| Alert | Condition | Severity |
|---|---|---|
| `DriftwatchApiDown` | `up == 0` (or the job absent) for 1m | critical |
| `DriftwatchHostNotReporting` | last seen more than 10m ago, for 2m | warning |
| `DriftwatchCriticalControlFailing` | at least one critical check failing, for 5m (keeps firing 2m) | critical |
| `DriftwatchHostComplianceLow` | score below 70, for 15m (keeps firing 5m) | warning |
| `DriftwatchComplianceRegression` | a host had a `REGRESSED` drift event in the last 10m (one alert per host) | info |

Per-host alerts keep the series' `host_id` (identity) and `hostname` (for people) labels.
`keep_firing_for` on the two host-state alerts stops a single failed scrape, or a score
hovering at 70, from resolving and re-firing them. `DriftwatchComplianceRegression` uses the
per-host counter `driftwatch_host_drift_events_total`, so each alert names its host. On
`DriftwatchCriticalControlFailing`, the metric's own `severity` label (the check severity) is
aggregated away, so the alert's `severity` label means only one thing.

[`alertmanager/alertmanager.yml`](alertmanager/alertmanager.yml) routes on `severity` to
`alert-sink` paths `/alerts/critical`, `/alerts/warning` and `/alerts/info`, so the sink log
shows which route each notification took. Alerts are grouped by `alertname`, so many hosts
failing one control arrive as one notification. Critical alerts notify after 10 s and
repeat hourly, warnings after 30 s and every 4 h, info alerts after 30 s and every 12 h.
`DriftwatchApiDown` inhibits the other Driftwatch alerts, whose data disappears along with
the API.

### What fires in the demo, and why

With the whole stack and the four demo hosts running:

- **`DriftwatchCriticalControlFailing` for `debian-legacy`**, about 5 minutes after its first
  scan. The host is built with an extra UID-0 account, so `accounts.uid0_only_root`
  (critical) fails on every scan.
- **`DriftwatchComplianceRegression` for `alpine-drifting`**, within a few minutes and then
  for as long as the demo runs. `alpine-drifting` flips `PermitRootLogin` between `no` and `yes` every
  `DRIFT_PERIOD` (120 s by default). Each flip to `yes` makes `ssh.permit_root_login`
  regress on the next scan.
- `DriftwatchHostComplianceLow` fires 15 minutes in for any host whose score is below 70.
  Which hosts that is depends on the demo postures, so check "Current score, lowest first".

Stopping a host (`docker compose stop host-rocky-web`) fires `DriftwatchHostNotReporting`
after about 12 minutes. Stopping the API (`docker compose stop api`) fires
`DriftwatchApiDown` after a minute.

## Dashboard

"Driftwatch fleet compliance" (uid `driftwatch-fleet`), with a `Host` filter:

- Headline tiles: fleet average score, hosts online and stale, hosts failing a critical
  check, regressions in the selected range, Driftwatch alerts firing.
- Compliance: score by host over time (dashed line at the 70 alert threshold), current score
  ranked lowest first, a table of hosts failing critical checks, failing checks stacked by
  severity.
- Drift: drift events per interval (1 minute or more), with regressions plotted up and
  resolutions down, and a table of regressions by host in the selected range.
- API: scan ingest rate by outcome, ingest latency p50/p95 from
  `driftwatch_scan_ingest_duration_seconds`, and the 5xx and 4xx share of API requests.
  `/actuator` endpoints are left out of the error ratio, because health probes and
  scrapes would otherwise dilute it.

Firing Driftwatch alerts show as annotations on the time-series panels.

The per-host metrics are one series per host for score and last seen, four (one per
severity) for failing checks, and up to three (one per direction) for drift events: at most
nine series per host. That's fine for fleets of hundreds of hosts.
`hostname` is a label, so renaming a host starts new series, and the old ones go stale.

## Testing the rules

[`prometheus/tests/driftwatch_test.yml`](prometheus/tests/driftwatch_test.yml) holds
`promtool` unit tests. For each alert there are cases that fire, cases that don't, both sides
of the threshold and the `for:` boundary, and recovery. Missed scrapes, counter resets and
hosts without a score are covered too. From the repository root, in Git Bash
(`MSYS_NO_PATHCONV=1` stops Git Bash from rewriting the container paths):

```sh
MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD/deploy/observability/prometheus:/etc/prometheus:ro" \
  --entrypoint promtool prom/prometheus:v3.15.1 check config /etc/prometheus/prometheus.yml
MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD/deploy/observability/prometheus:/etc/prometheus:ro" \
  --entrypoint promtool prom/prometheus:v3.15.1 test rules /etc/prometheus/tests/driftwatch_test.yml
MSYS_NO_PATHCONV=1 docker run --rm -v "$PWD/deploy/observability/alertmanager:/etc/alertmanager:ro" \
  --entrypoint amtool prom/alertmanager:v0.34.1 check-config /etc/alertmanager/alertmanager.yml
```

To test the notification path without the rest of the stack, run this directory on its own.
The API target doesn't exist there, so `DriftwatchApiDown` fires after a minute and shows up
in the sink log on `/alerts/critical`:

```sh
docker compose -p driftwatch-obs-check -f deploy/observability/compose.yml --profile observability up -d --wait
docker compose -p driftwatch-obs-check -f deploy/observability/compose.yml logs -f alert-sink
docker compose -p driftwatch-obs-check -f deploy/observability/compose.yml --profile observability down -v
```

Prometheus has no `--web.enable-lifecycle`, so it can't be reloaded over HTTP. After editing
the rules, run `docker compose kill -s SIGHUP prometheus`, or restart it.

## Files

| Path | What |
|---|---|
| `compose.yml` | the four services, profile `observability` |
| `prometheus/prometheus.yml` | scrape jobs (`driftwatch-api`, plus Prometheus and Alertmanager themselves) |
| `prometheus/rules/driftwatch.yml` | the alert rules |
| `prometheus/tests/driftwatch_test.yml` | `promtool test rules` suite |
| `alertmanager/alertmanager.yml` | routing, grouping, inhibition, receivers |
| `grafana/provisioning/` | datasource (uid `driftwatch-prometheus`) and dashboard provider |
| `grafana/dashboards/driftwatch-fleet.json` | the dashboard |
| `RUNBOOK.md` | what to do when each alert fires |
