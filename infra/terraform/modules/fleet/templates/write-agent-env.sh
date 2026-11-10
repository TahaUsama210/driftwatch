#!/bin/sh
# ExecStartPre (runs as root, outside the sandbox): reads the enrollment token from SSM and
# writes it to /run/driftwatch-agent/agent.env for systemd's EnvironmentFile. /run is a
# tmpfs, so the token never reaches persistent storage, and every start picks up a
# rotated token.
set -eu
umask 077
dir=/run/driftwatch-agent
mkdir -p "$dir"
chmod 0700 "$dir"
token=$(/usr/local/libexec/driftwatch/get-enrollment-token)
[ -n "$token" ] || { echo "write-agent-env: empty enrollment token" >&2; exit 1; }
printf 'DRIFTWATCH_ENROLLMENT_TOKEN=%s\n' "$token" > "$dir/agent.env.tmp"
mv -f "$dir/agent.env.tmp" "$dir/agent.env"
