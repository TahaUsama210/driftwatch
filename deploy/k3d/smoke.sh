#!/usr/bin/env bash
# End-to-end smoke test for the k3d deployment, entirely through the ingress at
# http://localhost:38080 (Traefik -> web nginx -> api / Keycloak).
#
#   deploy/k3d/up.sh && deploy/k3d/smoke.sh
#
# Checks: API readiness through the ingress; Keycloak serving the realm with the
# k3d issuer; the dashboard and its runtime OIDC config; an unauthenticated API read
# is 401; a viewer token from the client-credentials grant reads the API; and every
# k3d node appears as an enrolled host with at least one scan (the agent DaemonSet).
# Every check runs and is reported, then the script exits non-zero if any failed.
#
# Needs bash, curl, jq and kubectl (kubectl only to list the nodes). Without curl or
# jq locally (e.g. Git Bash on Windows) it runs the HTTP checks in a pinned Alpine
# container that reaches the host's port through host.docker.internal (Docker
# Desktop) or the host network (Linux).
#
# Environment:
#   BASE_URL                    ingress base URL         (default http://localhost:38080)
#   OIDC_ISSUER                 issuer tokens must carry (default $BASE_URL/auth/realms/driftwatch)
#   SMOKE_VIEWER_CLIENT_SECRET  demo realm's viewer client secret by default
#   SMOKE_TIMEOUT               seconds to wait for readiness and for scans (default 240)
#   SMOKE_EXPECTED_HOSTS        space-separated hostnames; default: the cluster's node names

# jq programs reference jq variables that the shell must not expand.
# shellcheck disable=SC2016
set -euo pipefail

readonly SMOKE_RUNNER_IMAGE=alpine:3.24.3

BASE_URL="${BASE_URL:-http://localhost:38080}"
BASE_URL="${BASE_URL%/}"
OIDC_ISSUER="${OIDC_ISSUER:-http://localhost:38080/auth/realms/driftwatch}"
VIEWER_CLIENT_SECRET="${SMOKE_VIEWER_CLIENT_SECRET:-smoke-viewer-demo-secret}"
SMOKE_TIMEOUT="${SMOKE_TIMEOUT:-240}"
readonly VIEWER_CLIENT_ID=driftwatch-smoke-viewer
readonly WEB_CLIENT_ID=driftwatch-web

# ---------------------------------------------------------------------------
# Bootstrap: find the expected hosts, and move into a container if needed.
# ---------------------------------------------------------------------------

if [[ -z "${SMOKE_IN_CONTAINER:-}" ]]; then
  # shellcheck source=lib.sh
  source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

  if [[ -z "${SMOKE_EXPECTED_HOSTS:-}" ]]; then
    require K3D KUBECTL
    cluster_exists || die "cluster $CLUSTER_NAME does not exist; run deploy/k3d/up.sh first"
    use_cluster_kubeconfig
    # The agent reports /etc/hostname of the node, which k3d sets to the node name.
    SMOKE_EXPECTED_HOSTS="$("$KUBECTL" get nodes -o jsonpath='{.items[*].metadata.name}')"
  fi

  if ! command -v curl >/dev/null 2>&1 || ! command -v jq >/dev/null 2>&1; then
    require DOCKER
    container_base_url="$BASE_URL"
    network_args=(--network host)
    if [[ "$(uname -s)" != Linux ]]; then
      # Docker Desktop: the container reaches the host's 127.0.0.1 ports through
      # host.docker.internal. Keycloak's issuer is fixed, so tokens are unaffected.
      container_base_url="${BASE_URL/localhost/host.docker.internal}"
      container_base_url="${container_base_url/127.0.0.1/host.docker.internal}"
      network_args=()
    fi
    log "curl or jq missing locally; running the checks in $SMOKE_RUNNER_IMAGE"
    exec "$DOCKER" run --rm "${network_args[@]}" \
      -v "$K3D_DIR:/smoke:ro" \
      -e SMOKE_IN_CONTAINER=1 \
      -e BASE_URL="$container_base_url" \
      -e OIDC_ISSUER="$OIDC_ISSUER" \
      -e SMOKE_VIEWER_CLIENT_SECRET="$VIEWER_CLIENT_SECRET" \
      -e SMOKE_TIMEOUT="$SMOKE_TIMEOUT" \
      -e SMOKE_EXPECTED_HOSTS="$SMOKE_EXPECTED_HOSTS" \
      "$SMOKE_RUNNER_IMAGE" \
      sh -c 'apk add --no-cache --quiet bash curl jq && exec bash /smoke/smoke.sh'
  fi
else
  log() { printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; }
fi

read -r -a EXPECTED_HOSTS <<<"$SMOKE_EXPECTED_HOSTS"
[[ ${#EXPECTED_HOSTS[@]} -gt 0 ]] || { log "ERROR: no expected hosts"; exit 1; }

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

workdir="$(mktemp -d)"
trap 'rm -rf "$workdir"' EXIT
readonly BODY="$workdir/body" HEADERS="$workdir/headers" AUTH="$workdir/auth"

failures=()
pass() { printf 'PASS  %s\n' "$1"; }
fail() { printf 'FAIL  %s: %s\n' "$1" "$2"; failures+=("$1"); }

# http METHOD PATH [curl args...] -> prints the status code; body in $BODY.
http() {
  local method="$1" path="$2"; shift 2
  curl -sS -o "$BODY" -D "$HEADERS" -w '%{http_code}' -X "$method" --max-time 15 "$@" \
    "$BASE_URL$path" 2>/dev/null || printf '000'
}

# Retries http GET until it returns the wanted status or SMOKE_TIMEOUT passes.
wait_for_status() {
  local want="$1" path="$2" status deadline=$((SECONDS + SMOKE_TIMEOUT))
  while :; do
    status="$(http GET "$path")"
    [[ "$status" == "$want" ]] && return 0
    ((SECONDS < deadline)) || { printf '%s' "$status"; return 1; }
    sleep 3
  done
}

header() { grep -i "^$1:" "$HEADERS" | head -n1 | cut -d: -f2- | tr -d '\r' | sed 's/^ *//'; }

# ---------------------------------------------------------------------------
# Checks
# ---------------------------------------------------------------------------

log "smoke testing $BASE_URL (expecting hosts: ${EXPECTED_HOSTS[*]})"

check="API readiness through the ingress"
if status="$(wait_for_status 200 /actuator/health/readiness)"; then
  if [[ "$(jq -r .status "$BODY" 2>/dev/null)" == UP ]]; then pass "$check"; else fail "$check" "status is $(jq -c . "$BODY")"; fi
else
  fail "$check" "HTTP $status after ${SMOKE_TIMEOUT}s"
fi

check="Keycloak serves the realm with the k3d issuer"
status="$(http GET /auth/realms/driftwatch/.well-known/openid-configuration)"
if [[ "$status" == 200 ]] && [[ "$(jq -r .issuer "$BODY" 2>/dev/null)" == "$OIDC_ISSUER" ]]; then
  pass "$check"
else
  fail "$check" "HTTP $status, issuer $(jq -r .issuer "$BODY" 2>/dev/null || echo '?')"
fi

check="dashboard is served"
status="$(http GET /)"
if [[ "$status" == 200 ]] && [[ "$(header content-type)" == text/html* ]] && grep -q 'id="root"' "$BODY"; then
  pass "$check"
else
  fail "$check" "HTTP $status, content-type $(header content-type)"
fi

check="dashboard runtime config names the k3d issuer"
status="$(http GET /config.json)"
if [[ "$status" == 200 ]] &&
  jq -e --arg a "$OIDC_ISSUER" --arg c "$WEB_CLIENT_ID" '.oidc.authority == $a and .oidc.clientId == $c' "$BODY" >/dev/null 2>&1; then
  pass "$check"
else
  fail "$check" "HTTP $status, content-type $(header content-type)"
fi

check="unauthenticated API read is 401"
status="$(http GET /api/v1/hosts)"
if [[ "$status" == 401 ]] && [[ "$(header www-authenticate)" == Bearer* ]]; then
  pass "$check"
else
  fail "$check" "HTTP $status, WWW-Authenticate '$(header www-authenticate)'"
fi

check="viewer token from the client-credentials grant"
status="$(http POST /auth/realms/driftwatch/protocol/openid-connect/token \
  --data-urlencode grant_type=client_credentials \
  --data-urlencode "client_id=$VIEWER_CLIENT_ID" \
  --data-urlencode "client_secret=$VIEWER_CLIENT_SECRET")"
have_token=false
if [[ "$status" == 200 ]] && token="$(jq -er .access_token "$BODY")"; then
  # The token goes to curl in a header file: never on a command line or in output.
  (umask 077 && printf 'Authorization: Bearer %s\n' "$token" >"$AUTH")
  unset token
  have_token=true
  pass "$check"
else
  fail "$check" "HTTP $status"
fi

check="viewer reads the API"
if $have_token; then
  status="$(http GET /api/v1/hosts -H "@$AUTH")"
  if [[ "$status" == 200 ]]; then pass "$check"; else fail "$check" "HTTP $status"; fi
else
  fail "$check" "no viewer token"
fi

check="every node is enrolled and has a scan"
auth_args=()
$have_token && auth_args=(-H "@$AUTH")
deadline=$((SECONDS + SMOKE_TIMEOUT))
expected_json="$(printf '%s\n' "${EXPECTED_HOSTS[@]}" | jq -R . | jq -sc .)"
while :; do
  status="$(http GET '/api/v1/hosts?size=200' "${auth_args[@]}")"
  missing=""
  if [[ "$status" == 200 ]]; then
    missing="$(jq -r --argjson want "$expected_json" '
      [.items[] | select(.latestScan != null) | .hostname] as $scanned
      | $want - $scanned | join(" ")' "$BODY")"
    [[ -z "$missing" ]] && break
  fi
  if ((SECONDS >= deadline)); then
    fail "$check" "HTTP $status; without a scan after ${SMOKE_TIMEOUT}s: ${missing:-unknown}"
    break
  fi
  sleep 5
done
if [[ "$status" == 200 && -z "$missing" ]]; then
  pass "$check ($(jq -r --argjson want "$expected_json" '
    [.items[] | select(.hostname as $h | $want | index($h))
     | "\(.hostname)=\(.latestScan.score // "n/a")"] | join(", ")' "$BODY"))"
fi

echo
if ((${#failures[@]} > 0)); then
  printf '%d check(s) failed:\n' "${#failures[@]}"
  printf '  - %s\n' "${failures[@]}"
  exit 1
fi
echo "all smoke checks passed"
