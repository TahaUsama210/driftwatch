# Demo fleet: simulated hosts

Four containers on different distributions with deliberately different
security postures, so the dashboard shows a realistic spread of scores and live
drift. Each image installs the real OpenSSH server and cron packages, applies a
posture on top of the distribution's stock configuration, and runs
`driftwatch-agent run` as its entrypoint. The agent is built from `../../agent`
in a build stage, so every Dockerfile here is built with the **repository
root** as its context:

```sh
docker build -f deploy/hosts/ubuntu-hardened/Dockerfile -t driftwatch-host-ubuntu-hardened:dev .
docker build -f deploy/hosts/debian-legacy/Dockerfile   -t driftwatch-host-debian-legacy:dev .
docker build -f deploy/hosts/rocky-web/Dockerfile       -t driftwatch-host-rocky-web:dev .
docker build -f deploy/hosts/alpine-drifting/Dockerfile -t driftwatch-host-alpine-drifting:dev .
```

Each `Dockerfile` has a `Dockerfile.dockerignore` beside it. BuildKit uses it
instead of a root `.dockerignore`, so the context contains only the agent
sources and that host's directory. `VERSION` and `COMMIT` build args set the
agent's reported version (default `dev` / `unknown`).

## Running them

The images set `DRIFTWATCH_INTERVAL=30s`. Compose provides the rest:

| Setting | Value |
|---|---|
| `hostname:` | the service name without `host-`, e.g. `ubuntu-hardened`. The agent reports the kernel hostname, so this is what the dashboard shows. |
| `DRIFTWATCH_SERVER_URL` | `http://api:8080` |
| `DRIFTWATCH_ENROLLMENT_TOKEN` | the API's enrollment token |
| `sysctls:` | per host, see below |
| volume at `/var/lib/driftwatch` | recommended. None of these images has a usable `/etc/machine-id`, so each container generates a machine ID once and keeps it in `state.json`. Without a volume, a recreated container (`docker compose down && up`) enrolls as a new host. |

The agent runs as root, because it reads `/etc/shadow`. It handles SIGTERM
itself: with nothing in flight it exits immediately, and a request already on
the wire gets up to 5 s. A `stop_grace_period` of at least 6 s lets such a
request finish (Docker's default is 10 s). On the Alpine host, `tini` is PID 1
and forwards signals to the agent. On the others the agent is PID 1.

Example service, equivalent to the one in the root `docker-compose.yml`
(which shares most of it between the hosts through YAML anchors):

```yaml
host-ubuntu-hardened:
  build:
    context: .
    dockerfile: deploy/hosts/ubuntu-hardened/Dockerfile
  hostname: ubuntu-hardened
  environment:
    DRIFTWATCH_SERVER_URL: http://api:8080
    DRIFTWATCH_ENROLLMENT_TOKEN: ${DRIFTWATCH_ENROLLMENT_TOKEN}
  sysctls:
    net.ipv4.ip_forward: 0
    net.ipv4.conf.all.accept_source_route: 0
    net.ipv4.tcp_syncookies: 1
  volumes:
    - host-ubuntu-hardened-state:/var/lib/driftwatch
  stop_grace_period: 10s
```

## Kernel parameters

The four `network.*` checks read the container's own network namespace, so
compose `sysctls:` decides them. `kernel.randomize_va_space` is not
namespaced. It reflects the Docker host (2 on Docker Desktop, so
`kernel.aslr_enabled` passes everywhere).

Every host gets `net.ipv4.ip_forward=0`, `net.ipv4.conf.all.accept_source_route=0`
and `net.ipv4.tcp_syncookies=1`, with these exceptions:

| Host | Overrides | Network checks that fail |
|---|---|---|
| `host-ubuntu-hardened` | none | `icmp_redirects_rejected` |
| `host-debian-legacy` | `net.ipv4.conf.all.accept_redirects=1`, `net.ipv4.conf.all.accept_source_route=1` | `icmp_redirects_rejected`, `source_routing_rejected` |
| `host-rocky-web` | `net.ipv4.ip_forward=1` | `ip_forwarding_disabled` |
| `host-alpine-drifting` | none | `icmp_redirects_rejected` |

Two kernel behaviours matter here:

- A fresh container on Docker Desktop starts with `ip_forward=1`,
  `accept_redirects=0`, `accept_source_route=0` and `tcp_syncookies=1`.
- Turning forwarding **off** makes the kernel set
  `net.ipv4.conf.all.accept_redirects` to 1 (`inet_forward_change()` sets it to
  `!forwarding`). Asking for `accept_redirects: "0"` alongside
  `ip_forward: "0"` is not reliable, because the runtime applies sysctls in no
  fixed order. On Docker 29.4 it ended up as `accept_redirects=1` in 4 of 5
  runs. So compose never asks for `accept_redirects=0` on a host that turns
  forwarding off, and those hosts fail the redirects check every time instead
  of at random. `host-debian-legacy` asks for `accept_redirects=1`, which is
  what the kernel sets anyway, so the order does not matter there.

## Postures and expected results

Measured with `docker run --rm --hostname <name> --sysctl ... <image> scan`
using the sysctls above. Total weight is 49 (48 when a check is not applicable).

| Host | Base | Score | Pass / Fail / N/A | Failing checks |
|---|---|---|---|---|
| `host-ubuntu-hardened` | `ubuntu:24.04` | **93.9** | 19 / 2 / 0 | `ssh.idle_timeout`, `network.icmp_redirects_rejected` |
| `host-alpine-drifting` | `alpine:3.22` | **95.8** ↔ **89.6** | 19 / 1 / 1 ↔ 18 / 2 / 1 | `network.icmp_redirects_rejected`, plus `ssh.permit_root_login` while drifted |
| `host-rocky-web` | `rockylinux/rockylinux:9.8` | **83.7** | 16 / 5 / 0 | `ssh.permit_root_login`, `ssh.x11_forwarding`, `accounts.pass_min_days`, `network.ip_forwarding_disabled`, `filesystem.crontab_permissions` |
| `host-debian-legacy` | `debian:12.14` | **46.9** | 8 / 13 / 0 | every `ssh.*` check except `permit_empty_passwords`, `accounts.uid0_only_root`, `accounts.pass_max_days`, `accounts.pass_min_days`, `network.icmp_redirects_rejected`, `network.source_routing_rejected`, `filesystem.crontab_permissions` |

### ubuntu-hardened

The drop-in `10-hardening.conf` is included before the rest of the stock
`sshd_config`, so its values win, including `X11Forwarding no` over the stock
file's later `X11Forwarding yes`. `sshd_config` and `/etc/crontab` are 0600.
`login.defs` sets 365 / 1 / 14 days. The deliberate gap: `ClientAliveInterval`
is never set.

### debian-legacy

Settings uncommented in the main `sshd_config`: `PermitRootLogin yes`,
`PasswordAuthentication yes`, `MaxAuthTries 10`, `LoginGraceTime 2m`. The
stock file's `X11Forwarding yes` and 0644 mode stay as they are. `login.defs`
keeps Debian's defaults (`PASS_MAX_DAYS 99999`, `PASS_MIN_DAYS 0`). A second
UID 0 account, `toor`, is created with
`useradd --non-unique --uid 0`, so `accounts.uid0_only_root` fails. Its
password is locked, so `accounts.no_empty_passwords` still passes.

### rocky-web

`40-web.conf` sorts before the vendor's `50-redhat.conf`. It disables
password authentication and sets `MaxAuthTries 4`, `LoginGraceTime 60` and
`ClientAliveInterval 600`. It leaves `PermitRootLogin` at the OpenSSH default
(`prohibit-password`, a FAIL) and does not override `50-redhat.conf`'s
`X11Forwarding yes`. `PASS_MAX_DAYS` is 90 but `PASS_MIN_DAYS` keeps Rocky's
0. `/etc/crontab` keeps its stock 0644. The Docker Hub library image
`rockylinux` stopped receiving updates in 2024, so the image uses the Rocky
project's `rockylinux/rockylinux`.

### alpine-drifting

Everything is hardened (`10-hardening.conf`, a full `login.defs` from the
`shadow` package). Alpine has no `/etc/crontab` (busybox crond uses
`/etc/crontabs/`), so `filesystem.crontab_permissions` is NOT_APPLICABLE.

`drift-entrypoint.sh` writes `PermitRootLogin no` to
`/etc/ssh/sshd_config.d/00-drift.conf` at startup. It then flips the value
between `no` and `yes` every `DRIFT_PERIOD` seconds (default 120, must be a
positive integer) in a background loop, and `exec`s the agent. The file sorts
before `10-hardening.conf`, so it always decides `PermitRootLogin`. It is
replaced with write-then-rename, so a scan never reads a partial file. With
30 s scans and a 120 s period, the API records a REGRESSED event and then a
RESOLVED event for `ssh.permit_root_login` every four minutes.

## Line endings

`.gitattributes` in this directory forces LF for every file, including on
Windows checkouts with `core.autocrlf=true`. A CRLF would break the shell
entrypoint's shebang and leave stray `\r` in sshd and login.defs values.
