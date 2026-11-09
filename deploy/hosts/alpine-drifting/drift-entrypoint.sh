#!/bin/sh
# Entrypoint for the drifting demo host.
#
# Starts compliant (PermitRootLogin no), then flips the setting between "no"
# and "yes" every DRIFT_PERIOD seconds in the background, and finally execs the
# agent with the container's arguments. tini is PID 1: it forwards SIGTERM to
# the agent (this shell's PID after exec) and reaps the background loop.
set -eu

period=${DRIFT_PERIOD:-120}
case $period in
  '' | *[!0-9]* | 0*)
    echo "drift-entrypoint: DRIFT_PERIOD must be a positive number of seconds, got '$period'" >&2
    exit 64
    ;;
esac

conf=/etc/ssh/sshd_config.d/00-drift.conf

# set_permit_root_login writes the drop-in atomically (write, then rename) so
# the agent never reads a half-written file. The .tmp name does not match the
# *.conf Include pattern.
set_permit_root_login() {
  printf 'PermitRootLogin %s\n' "$1" > "$conf.tmp"
  chmod 0600 "$conf.tmp"
  mv "$conf.tmp" "$conf"
  echo "drift-entrypoint: PermitRootLogin set to $1" >&2
}

set_permit_root_login no

(
  value=no
  while :; do
    sleep "$period"
    if [ "$value" = no ]; then value=yes; else value=no; fi
    set_permit_root_login "$value"
  done
) &

exec /usr/local/bin/driftwatch-agent "$@"
