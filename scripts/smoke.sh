#!/usr/bin/env bash
#
# End-to-end smoke test for a running Driftwatch stack.
#
#   docker compose up -d --build --wait && bash scripts/smoke.sh
#
# Verifies, against real containers, that every demo host enrolls and reports, that
# the API scores the hardened host above the legacy one, that the read models respond
# with the documented shapes, that the drifting host produces drift events, that agent
# endpoints reject unauthenticated callers, and that the dashboard container serves the
# SPA and proxies the API.
#
# Requires bash, curl and jq. On Windows, run it from Git Bash.
#
# Environment:
#   BASE_URL        API base URL                              (default http://localhost:8480)
#   WEB_URL         dashboard base URL                        (default http://localhost:8400)
#   SMOKE_TIMEOUT   seconds the whole run may take, including
#                   waiting for scans and drift               (default 300)
#   POLL_INTERVAL   seconds between polls                     (default 5)

# jq programs reference jq variables ($h, $n, ...) that the shell must not expand.
# shellcheck disable=SC2016

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8480}"
BASE_URL="${BASE_URL%/}"
WEB_URL="${WEB_URL:-http://localhost:8400}"
WEB_URL="${WEB_URL%/}"
SMOKE_TIMEOUT="${SMOKE_TIMEOUT:-300}"
POLL_INTERVAL="${POLL_INTERVAL:-5}"

readonly EXPECTED_HOSTS=(ubuntu-hardened debian-legacy rocky-web alpine-drifting)
readonly HARDENED_HOST=ubuntu-hardened
readonly LEGACY_HOST=debian-legacy
readonly DRIFTING_HOST=alpine-drifting
# debian-legacy ships an extra UID-0 account, so this check must fail there.
readonly LEGACY_FAILING_CHECK=accounts.uid0_only_root

readonly DEADLINE=$((SECONDS + SMOKE_TIMEOUT))
TMP_DIR="$(mktemp -d)"
readonly TMP_DIR
trap 'rm -rf "$TMP_DIR"' EXIT

# Set by request(); fail() prints them so a failure shows what the script last saw.
LAST_REQUEST=""
LAST_STATUS=""
LAST_CONTENT_TYPE=""
LAST_BODY=""
PASSED=0

log() { printf '%s [smoke] %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; }

pass() {
  PASSED=$((PASSED + 1))
  log "PASS  $*"
}

fail() {
  log "FAIL  $*"
  if [[ -n $LAST_REQUEST ]]; then
    log "last request: ${LAST_REQUEST} -> HTTP ${LAST_STATUS} (${LAST_CONTENT_TYPE:-no content type})"
    if jq . <<<"$LAST_BODY" >/dev/null 2>&1; then
      jq . <<<"$LAST_BODY" | head -c 4000 >&2
    else
      printf '%s' "$LAST_BODY" | head -c 4000 >&2
    fi
    printf '\n' >&2
  fi
  exit 1
}

# request METHOD URL [curl args...]
# Never fails: connection errors are recorded as HTTP 000 with curl's message as the body.
request() {
  local method=$1 url=$2
  shift 2
  local meta
  LAST_REQUEST="${method} ${url}"
  meta=$(curl --silent --show-error --max-time 10 --request "$method" \
    --output "$TMP_DIR/body" --write-out '%{http_code}\t%{content_type}' \
    "$@" "$url" 2>"$TMP_DIR/err") || true
  LAST_STATUS=${meta%%$'\t'*}
  LAST_CONTENT_TYPE=${meta#*$'\t'}
  if [[ -z $LAST_STATUS || $LAST_STATUS == 000 ]]; then
    LAST_STATUS=000
    LAST_CONTENT_TYPE=""
    LAST_BODY=$(cat "$TMP_DIR/err")
  else
    LAST_BODY=$(cat "$TMP_DIR/body")
  fi
}

# json_matches JQ_FILTER [jq args...] -- true when the filter yields true for LAST_BODY.
json_matches() {
  local filter=$1
  shift
  jq -e "$filter" "$@" <<<"$LAST_BODY" >/dev/null 2>&1
}

# expect_json DESCRIPTION URL JQ_FILTER [jq args...]
# GETs URL and requires HTTP 200, a JSON content type, and a body matching the filter.
expect_json() {
  local description=$1 url=$2 filter=$3
  shift 3
  request GET "$url" --header 'Accept: application/json'
  [[ $LAST_STATUS == 200 ]] || fail "${description}: expected HTTP 200"
  [[ $LAST_CONTENT_TYPE == application/json* ]] || fail "${description}: expected application/json"
  json_matches "$filter" "$@" || fail "${description}: unexpected response shape"
  pass "$description"
}

# wait_until DESCRIPTION PREDICATE [args...] -- polls PREDICATE until it succeeds or the deadline passes.
wait_until() {
  local description=$1
  shift
  log "waiting for ${description} (up to $((DEADLINE - SECONDS))s left)"
  until "$@"; do
    ((SECONDS < DEADLINE)) || fail "timed out waiting for ${description}"
    sleep "$POLL_INTERVAL"
  done
  pass "$description"
}

require_tools() {
  local tool
  for tool in curl jq; do
    command -v "$tool" >/dev/null 2>&1 || fail "${tool} is required but not installed"
  done
}

# --- predicates (used in conditions, so they return a status instead of exiting) ---------

api_ready() {
  request GET "$BASE_URL/actuator/health/readiness"
  [[ $LAST_STATUS == 200 ]] && json_matches '.status == "UP"'
}

web_serving() {
  request GET "$WEB_URL/"
  [[ $LAST_STATUS == 200 ]]
}

LAST_SCANNED=""
all_hosts_scanned() {
  request GET "$BASE_URL/api/v1/hosts?size=200"
  [[ $LAST_STATUS == 200 ]] || return 1
  local scanned
  scanned=$(jq -r '[.items[] | select(.latestScan != null) | .hostname] | unique | join(", ")' \
    <<<"$LAST_BODY" 2>/dev/null) || return 1
  if [[ $scanned != "$LAST_SCANNED" ]]; then
    log "hosts with a scan so far: ${scanned:-none}"
    LAST_SCANNED=$scanned
  fi
  json_matches '($ARGS.positional - [.items[] | select(.latestScan != null) | .hostname]) == []' \
    --args "${EXPECTED_HOSTS[@]}"
}

drift_detected() {
  request GET "$BASE_URL/api/v1/events?hostId=${DRIFTING_HOST_ID}&limit=50"
  [[ $LAST_STATUS == 200 ]] && json_matches 'length > 0'
}

# --- checks -------------------------------------------------------------------------------

check_fleet() {
  wait_until "all ${#EXPECTED_HOSTS[@]} demo hosts to report a scan" all_hosts_scanned

  # If a host was re-enrolled under a new identity, the most recently seen record wins.
  local latest
  latest=$(jq -c '.items | sort_by(.lastSeenAt) | map({key: .hostname, value: .}) | from_entries' \
    <<<"$LAST_BODY")

  local host
  for host in "${EXPECTED_HOSTS[@]}"; do
    jq -e --arg h "$host" '.[$h].status == "ONLINE"' <<<"$latest" >/dev/null \
      || fail "host ${host} is not ONLINE: $(jq -c --arg h "$host" '.[$h] | {status, lastSeenAt}' <<<"$latest")"
  done
  pass "all demo hosts are ONLINE"

  local scores
  scores=$(jq -c 'map_values(.latestScan.score)' <<<"$latest")
  log "latest scores: ${scores}"
  jq -e --arg weak "$LEGACY_HOST" --arg strong "$HARDENED_HOST" \
    '(.[$weak] | type == "number") and (.[$strong] | type == "number") and .[$weak] < .[$strong]' \
    <<<"$scores" >/dev/null \
    || fail "expected ${LEGACY_HOST} to score below ${HARDENED_HOST}, got ${scores}"
  pass "${LEGACY_HOST} scores below ${HARDENED_HOST}"

  DRIFTING_HOST_ID=$(jq -r --arg h "$DRIFTING_HOST" '.[$h].id' <<<"$latest")
}

check_read_models() {
  expect_json "fleet summary has the documented shape" "$BASE_URL/api/v1/fleet/summary" '
    .hostCount >= $n and .onlineCount >= $n and (.staleCount | type == "number")
    and (.averageScore | type == "number") and (.hostsWithCriticalFailures | type == "number")
    and (.topFailingChecks | type == "array" and length <= 5)
    and (.lastScanAt | type == "string")' --argjson n "${#EXPECTED_HOSTS[@]}"

  expect_json "fleet trend has the documented shape" "$BASE_URL/api/v1/fleet/trend?hours=1&bucketMinutes=5" '
    .bucketMinutes == 5 and (.points | type == "array" and length > 0)
    and all(.points[]; (.bucketStart | type == "string") and (.hostCount | type == "number")
      and (.averageScore | type | IN("number", "null")))'

  expect_json "check catalog lists checks with status counts" "$BASE_URL/api/v1/checks" '
    type == "array" and length > 0
    and all(.[]; (.id | type == "string") and (.severity | IN("low", "medium", "high", "critical"))
      and (.counts | (.pass + .fail + .error + .notApplicable) >= 1))'

  expect_json "${LEGACY_FAILING_CHECK} fails on ${LEGACY_HOST}" "$BASE_URL/api/v1/checks/${LEGACY_FAILING_CHECK}" '
    .id == $check and any(.hosts[]; .hostname == $host and .status == "FAIL")' \
    --arg check "$LEGACY_FAILING_CHECK" --arg host "$LEGACY_HOST"

  expect_json "drift events endpoint returns a list" "$BASE_URL/api/v1/events?limit=50" 'type == "array"'
}

# expect_unauthorized_scan DESCRIPTION [curl args...]
expect_unauthorized_scan() {
  local description=$1
  shift
  request POST "$BASE_URL/api/v1/scans" --header 'Content-Type: application/json' --data '{}' "$@"
  [[ $LAST_STATUS == 401 ]] || fail "scan submission with ${description}: expected HTTP 401"
  [[ $LAST_CONTENT_TYPE == application/problem+json* ]] \
    || fail "scan submission with ${description}: expected application/problem+json"
  json_matches '.status == 401 and (.title | type == "string") and (.type | type == "string")' \
    || fail "scan submission with ${description}: body is not RFC 9457 problem details"
  pass "scan submission with ${description} is rejected with 401 problem+json"
}

check_agent_auth() {
  expect_unauthorized_scan "no credentials"
  expect_unauthorized_scan "an invalid agent token" --header 'Authorization: Bearer dwa_not-a-real-token'
}

check_web() {
  wait_until "dashboard to serve on ${WEB_URL}" web_serving

  local path
  for path in / /hosts/00000000-0000-0000-0000-000000000000; do
    request GET "${WEB_URL}${path}"
    [[ $LAST_STATUS == 200 && $LAST_CONTENT_TYPE == text/html* ]] \
      || fail "dashboard ${path}: expected HTTP 200 text/html"
    [[ $LAST_BODY == *'<script'* ]] || fail "dashboard ${path}: response is not the SPA shell"
  done
  pass "dashboard serves the SPA shell, including for client-side routes"

  expect_json "dashboard proxies /api to the API" "$WEB_URL/api/v1/fleet/summary" \
    '.hostCount >= $n' --argjson n "${#EXPECTED_HOSTS[@]}"
  expect_json "dashboard proxies /actuator/health to the API" "$WEB_URL/actuator/health" '.status == "UP"'
}

check_drift() {
  wait_until "a drift event from ${DRIFTING_HOST}" drift_detected
  json_matches 'all(.[]; .hostname == $h and (.direction | IN("REGRESSED", "RESOLVED", "CHANGED"))
    and (.checkId | type == "string") and .fromStatus != .toStatus)' --arg h "$DRIFTING_HOST" \
    || fail "drift events for ${DRIFTING_HOST} have an unexpected shape"
  log "drift on ${DRIFTING_HOST}: $(jq -c '.[0] | {checkId, fromStatus, toStatus, direction, detectedAt}' <<<"$LAST_BODY")"
  pass "drift events for ${DRIFTING_HOST} have the documented shape"
}

main() {
  require_tools
  log "API ${BASE_URL}, dashboard ${WEB_URL}, timeout ${SMOKE_TIMEOUT}s"

  wait_until "API readiness" api_ready
  check_fleet
  check_read_models
  check_agent_auth
  check_web
  # Last, because it is the slowest: the drifting host needs a full flip period.
  check_drift

  log "all ${PASSED} checks passed in ${SECONDS}s"
}

main "$@"
