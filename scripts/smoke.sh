#!/usr/bin/env bash
#
# End-to-end smoke test for a running Driftwatch stack.
#
#   docker compose up -d --build --wait && bash scripts/smoke.sh
#
# Verifies, against real containers, that every demo host enrolls and reports, that
# the API scores the hardened host above the legacy one, that the read models respond
# with the documented shapes, that the drifting host produces drift events, and that the
# dashboard container serves the SPA, its runtime OIDC config, and proxies the API and
# Keycloak.
#
# Auth checks: dashboard endpoints reject anonymous and forged callers with 401, accept
# a viewer token, refuse a viewer's decommission with 403, and let an admin decommission
# a throwaway host this script enrolls itself (never a demo host), after which that
# host's agent token is rejected. JWTs are refused on agent endpoints and agent tokens
# on dashboard endpoints. Tokens come from Keycloak through the dashboard origin with
# the client-credentials grant, and are passed to curl in header files so they never
# appear on a command line or in the output.
#
# Requires bash, curl and jq. On Windows, run it from Git Bash.
#
# Environment:
#   BASE_URL              API base URL                                  (default http://localhost:8480)
#   WEB_URL               dashboard base URL, also serves /auth         (default http://localhost:8400)
#   OIDC_ISSUER           issuer every token must carry                 (default http://localhost:8400/auth/realms/driftwatch)
#   DRIFTWATCH_ENROLLMENT_TOKEN
#                         enrolls the throwaway host                    (default: the compose demo token)
#   SMOKE_VIEWER_CLIENT_SECRET, SMOKE_ADMIN_CLIENT_SECRET
#                         secrets of the smoke clients in the realm     (default: the demo realm's)
#   SMOKE_TIMEOUT         seconds the whole run may take, including
#                         waiting for scans and drift                   (default 300)
#   POLL_INTERVAL         seconds between polls                         (default 5)

# jq programs reference jq variables ($h, $n, ...) that the shell must not expand.
# shellcheck disable=SC2016

set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8480}"
BASE_URL="${BASE_URL%/}"
WEB_URL="${WEB_URL:-http://localhost:8400}"
WEB_URL="${WEB_URL%/}"
OIDC_ISSUER="${OIDC_ISSUER:-http://localhost:8400/auth/realms/driftwatch}"
ENROLLMENT_TOKEN="${DRIFTWATCH_ENROLLMENT_TOKEN:-insecure-local-demo-token-change-me}"
VIEWER_CLIENT_SECRET="${SMOKE_VIEWER_CLIENT_SECRET:-smoke-viewer-demo-secret}"
ADMIN_CLIENT_SECRET="${SMOKE_ADMIN_CLIENT_SECRET:-smoke-admin-demo-secret}"
SMOKE_TIMEOUT="${SMOKE_TIMEOUT:-300}"
POLL_INTERVAL="${POLL_INTERVAL:-5}"

readonly TOKEN_URL="${WEB_URL}/auth/realms/driftwatch/protocol/openid-connect/token"
readonly VIEWER_CLIENT_ID=driftwatch-smoke-viewer
readonly ADMIN_CLIENT_ID=driftwatch-smoke-admin
readonly WEB_CLIENT_ID=driftwatch-web

readonly EXPECTED_HOSTS=(ubuntu-hardened debian-legacy rocky-web alpine-drifting)
readonly HARDENED_HOST=ubuntu-hardened
readonly LEGACY_HOST=debian-legacy
readonly DRIFTING_HOST=alpine-drifting
# debian-legacy ships an extra UID-0 account, so this check must fail there.
readonly LEGACY_FAILING_CHECK=accounts.uid0_only_root

readonly DEADLINE=$((SECONDS + SMOKE_TIMEOUT))
TMP_DIR="$(mktemp -d)"
readonly TMP_DIR

# Set by request(); fail() prints them so a failure shows what the script last saw.
LAST_REQUEST=""
LAST_STATUS=""
LAST_CONTENT_TYPE=""
LAST_BODY=""
PASSED=0

# The host enrolled by check_decommission. Only this host is ever deleted.
THROWAWAY_HOST_ID=""
THROWAWAY_HOSTNAME=""
THROWAWAY_DELETED=false

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
# Response headers are kept in $TMP_DIR/headers.
request() {
  local method=$1 url=$2
  shift 2
  local meta
  LAST_REQUEST="${method} ${url}"
  : >"$TMP_DIR/headers"
  meta=$(curl --silent --show-error --max-time 10 --request "$method" \
    --output "$TMP_DIR/body" --dump-header "$TMP_DIR/headers" \
    --write-out '%{http_code}\t%{content_type}' "$@" "$url" 2>"$TMP_DIR/err") || true
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

# write_bearer NAME SECRET -- stores an Authorization header for call() to send as NAME.
write_bearer() {
  printf 'Authorization: Bearer %s\n' "$2" >"$TMP_DIR/$1.header"
}

# ensure_token viewer|admin
# Fetches a client-credentials access token through the dashboard origin, refreshing it
# a minute before it expires so long polls never send an expired token.
ensure_token() {
  local role=$1 client_id client_secret
  case $role in
    viewer) client_id=$VIEWER_CLIENT_ID client_secret=$VIEWER_CLIENT_SECRET ;;
    admin) client_id=$ADMIN_CLIENT_ID client_secret=$ADMIN_CLIENT_SECRET ;;
    *) fail "unknown role ${role}" ;;
  esac
  local expiry_file="$TMP_DIR/${role}.expires"
  if [[ -s $expiry_file ]] && ((SECONDS < $(<"$expiry_file"))); then
    return 0
  fi

  request POST "$TOKEN_URL" --user "${client_id}:${client_secret}" \
    --data grant_type=client_credentials
  [[ $LAST_STATUS == 200 ]] || fail "client-credentials token for ${client_id}: expected HTTP 200"
  local token expires_in
  token=$(jq -r '.access_token // empty' <<<"$LAST_BODY")
  expires_in=$(jq -r '.expires_in // 0' <<<"$LAST_BODY")
  [[ -n $token ]] || fail "client-credentials token for ${client_id}: no access_token in the response"
  LAST_BODY="(token response redacted)"

  write_bearer "$role" "$token"
  printf '%d\n' $((SECONDS + expires_in - 60)) >"$expiry_file"
}

# call METHOD URL CREDENTIAL [curl args...]
# CREDENTIAL is none, viewer or admin (Keycloak tokens), or the name of a header file
# written by write_bearer (enrollment, agent, forged).
call() {
  local method=$1 url=$2 credential=$3
  shift 3
  if [[ $credential == viewer || $credential == admin ]]; then
    ensure_token "$credential"
  fi
  if [[ $credential != none ]]; then
    set -- --header "@$TMP_DIR/${credential}.header" "$@"
  fi
  request "$method" "$url" "$@"
}

# json_matches JQ_FILTER [jq args...] -- true when the filter yields true for LAST_BODY.
json_matches() {
  local filter=$1
  shift
  jq -e "$filter" "$@" <<<"$LAST_BODY" >/dev/null 2>&1
}

# expect_json CREDENTIAL DESCRIPTION URL JQ_FILTER [jq args...]
# GETs URL and requires HTTP 200, a JSON content type, and a body matching the filter.
expect_json() {
  local credential=$1 description=$2 url=$3 filter=$4
  shift 4
  call GET "$url" "$credential" --header 'Accept: application/json'
  [[ $LAST_STATUS == 200 ]] || fail "${description}: expected HTTP 200"
  [[ $LAST_CONTENT_TYPE == application/json* ]] || fail "${description}: expected application/json"
  json_matches "$filter" "$@" || fail "${description}: unexpected response shape"
  pass "$description"
}

# expect_problem STATUS DESCRIPTION -- the last response is an RFC 9457 problem with STATUS.
# A 401 must also challenge with WWW-Authenticate: Bearer.
expect_problem() {
  local status=$1 description=$2
  [[ $LAST_STATUS == "$status" ]] || fail "${description}: expected HTTP ${status}"
  [[ $LAST_CONTENT_TYPE == application/problem+json* ]] \
    || fail "${description}: expected application/problem+json"
  json_matches '.status == ($s | tonumber) and (.title | type == "string") and (.type | type == "string")' \
    --arg s "$status" || fail "${description}: body is not RFC 9457 problem details"
  if [[ $status == 401 ]]; then
    grep -qi '^www-authenticate: *bearer' "$TMP_DIR/headers" \
      || fail "${description}: expected a WWW-Authenticate: Bearer challenge"
  fi
  pass "${description} is rejected with ${status} problem+json"
}

# expect_status STATUS DESCRIPTION -- the last response has STATUS.
expect_status() {
  [[ $LAST_STATUS == "$1" ]] || fail "$2: expected HTTP $1"
  pass "$2"
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
  for tool in curl jq od; do
    command -v "$tool" >/dev/null 2>&1 || fail "${tool} is required but not installed"
  done
}

# random_hex BYTES
random_hex() {
  od -An -N"$1" -tx1 /dev/urandom | tr -d ' \n'
}

# uuid4 -- a random RFC 9562 version 4 UUID.
uuid4() {
  local h
  h=$(random_hex 16)
  printf '%s-%s-4%s-%x%s-%s\n' "${h:0:8}" "${h:8:4}" "${h:13:3}" \
    $(((16#${h:16:1} & 3) | 8)) "${h:17:3}" "${h:20:12}"
}

# Removes the throwaway host if a failure stopped the run before it was decommissioned.
cleanup() {
  if [[ -n $THROWAWAY_HOST_ID && $THROWAWAY_DELETED == false ]]; then
    log "removing throwaway host ${THROWAWAY_HOSTNAME} (${THROWAWAY_HOST_ID})"
    (call DELETE "$BASE_URL/api/v1/hosts/${THROWAWAY_HOST_ID}" admin) || true
  fi
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

# --- predicates (used in conditions, so they return a status instead of exiting) ---------

api_ready() {
  call GET "$BASE_URL/actuator/health/readiness" none
  [[ $LAST_STATUS == 200 ]] && json_matches '.status == "UP"'
}

web_serving() {
  call GET "$WEB_URL/" none
  [[ $LAST_STATUS == 200 ]]
}

LAST_SCANNED=""
all_hosts_scanned() {
  call GET "$BASE_URL/api/v1/hosts?size=200" viewer
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
  call GET "$BASE_URL/api/v1/events?hostId=${DRIFTING_HOST_ID}&limit=50" viewer
  [[ $LAST_STATUS == 200 ]] && json_matches 'length > 0'
}

# --- checks -------------------------------------------------------------------------------

check_identity() {
  expect_json none "Keycloak discovery through the dashboard advertises the fixed issuer" \
    "$WEB_URL/auth/realms/driftwatch/.well-known/openid-configuration" \
    '.issuer == $iss and (.token_endpoint | startswith($iss))' --arg iss "$OIDC_ISSUER"

  expect_json none "dashboard serves its runtime OIDC config" "$WEB_URL/config.json" \
    '.oidc.authority == $iss and .oidc.clientId == $client' \
    --arg iss "$OIDC_ISSUER" --arg client "$WEB_CLIENT_ID"

  ensure_token viewer
  pass "smoke viewer obtains a client-credentials token through ${WEB_URL}/auth"
}

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
  expect_json viewer "fleet summary has the documented shape" "$BASE_URL/api/v1/fleet/summary" '
    .hostCount >= $n and .onlineCount >= $n and (.staleCount | type == "number")
    and (.averageScore | type == "number") and (.hostsWithCriticalFailures | type == "number")
    and (.topFailingChecks | type == "array" and length <= 5)
    and (.lastScanAt | type == "string")' --argjson n "${#EXPECTED_HOSTS[@]}"

  expect_json viewer "fleet trend has the documented shape" "$BASE_URL/api/v1/fleet/trend?hours=1&bucketMinutes=5" '
    .bucketMinutes == 5 and (.points | type == "array" and length > 0)
    and all(.points[]; (.bucketStart | type == "string") and (.hostCount | type == "number")
      and (.averageScore | type | IN("number", "null")))'

  expect_json viewer "check catalog lists checks with status counts" "$BASE_URL/api/v1/checks" '
    type == "array" and length > 0
    and all(.[]; (.id | type == "string") and (.severity | IN("low", "medium", "high", "critical"))
      and (.counts | (.pass + .fail + .error + .notApplicable) >= 1))'

  expect_json viewer "${LEGACY_FAILING_CHECK} fails on ${LEGACY_HOST}" "$BASE_URL/api/v1/checks/${LEGACY_FAILING_CHECK}" '
    .id == $check and any(.hosts[]; .hostname == $host and .status == "FAIL")' \
    --arg check "$LEGACY_FAILING_CHECK" --arg host "$LEGACY_HOST"

  expect_json viewer "drift events endpoint returns a list" "$BASE_URL/api/v1/events?limit=50" 'type == "array"'
}

check_dashboard_auth() {
  call GET "$BASE_URL/api/v1/hosts" none
  expect_problem 401 "GET /api/v1/hosts without credentials"

  write_bearer forged "eyJhbGciOiJub25lIn0.eyJzdWIiOiJzbW9rZSJ9."
  call GET "$BASE_URL/api/v1/hosts" forged
  expect_problem 401 "GET /api/v1/hosts with an unsigned JWT"

  call GET "$BASE_URL/api/v1/hosts" viewer
  expect_status 200 "GET /api/v1/hosts with a viewer token is allowed"

  call POST "$BASE_URL/api/v1/scans" viewer --header 'Content-Type: application/json' --data '{}'
  expect_problem 401 "scan submission with a dashboard JWT"
}

check_agent_auth() {
  call POST "$BASE_URL/api/v1/scans" none --header 'Content-Type: application/json' --data '{}'
  expect_problem 401 "scan submission with no credentials"

  write_bearer bogus-agent "dwa_not-a-real-token"
  call POST "$BASE_URL/api/v1/scans" bogus-agent --header 'Content-Type: application/json' --data '{}'
  expect_problem 401 "scan submission with an invalid agent token"
}

# submit_throwaway_scan -- submits a one-result scan with the throwaway host's agent token.
submit_throwaway_scan() {
  local now body
  now=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  body=$(jq -cn --arg scanId "$(uuid4)" --arg now "$now" --arg hostname "$THROWAWAY_HOSTNAME" '{
    scanId: $scanId, agentVersion: "smoke", startedAt: $now, finishedAt: $now,
    host: {hostname: $hostname, os: {id: "smoke", prettyName: "Driftwatch smoke test"},
           kernel: "smoke", arch: "amd64"},
    results: [{checkId: "kernel.aslr_enabled", category: "kernel", severity: "high", status: "PASS",
               title: "Full address space layout randomization is enabled"}]}')
  call POST "$BASE_URL/api/v1/scans" agent --header 'Content-Type: application/json' --data "$body"
}

check_decommission() {
  THROWAWAY_HOSTNAME="smoke-$(random_hex 4)"
  local enrollment
  enrollment=$(jq -cn --arg machineId "$(random_hex 16)" --arg hostname "$THROWAWAY_HOSTNAME" '{
    machineId: $machineId, agentVersion: "smoke", hostname: $hostname,
    os: {id: "smoke", prettyName: "Driftwatch smoke test"}, kernel: "smoke", arch: "amd64"}')
  write_bearer enrollment "$ENROLLMENT_TOKEN"
  call POST "$BASE_URL/api/v1/agents/enroll" enrollment \
    --header 'Content-Type: application/json' --data "$enrollment"
  # 201 means a new host: the random machineId cannot collide with a demo host's.
  [[ $LAST_STATUS == 201 ]] || fail "enrolling throwaway host ${THROWAWAY_HOSTNAME}: expected HTTP 201"
  THROWAWAY_HOST_ID=$(jq -r '.hostId // empty' <<<"$LAST_BODY")
  local agent_token
  agent_token=$(jq -r '.agentToken // empty' <<<"$LAST_BODY")
  LAST_BODY="(enrollment response redacted)"
  [[ -n $THROWAWAY_HOST_ID && -n $agent_token ]] || fail "enrollment response lacks hostId or agentToken"
  write_bearer agent "$agent_token"
  pass "enrolled throwaway host ${THROWAWAY_HOSTNAME} (${THROWAWAY_HOST_ID})"

  submit_throwaway_scan
  expect_status 201 "throwaway host's agent token can submit a scan"

  call GET "$BASE_URL/api/v1/hosts" agent
  expect_problem 401 "GET /api/v1/hosts with an agent token"

  local host_url="$BASE_URL/api/v1/hosts/${THROWAWAY_HOST_ID}"
  call DELETE "$host_url" viewer
  expect_problem 403 "decommission by a viewer"
  call GET "$host_url" viewer
  expect_status 200 "the throwaway host survives the viewer's decommission attempt"

  call DELETE "$host_url" admin
  expect_status 204 "admin decommissions the throwaway host"
  THROWAWAY_DELETED=true

  call GET "$host_url" viewer
  expect_status 404 "the decommissioned host is gone"

  submit_throwaway_scan
  expect_problem 401 "scan submission with the decommissioned host's agent token"
}

check_web() {
  local path
  for path in / /hosts/00000000-0000-0000-0000-000000000000; do
    call GET "${WEB_URL}${path}" none
    [[ $LAST_STATUS == 200 && $LAST_CONTENT_TYPE == text/html* ]] \
      || fail "dashboard ${path}: expected HTTP 200 text/html"
    [[ $LAST_BODY == *'<script'* ]] || fail "dashboard ${path}: response is not the SPA shell"
  done
  pass "dashboard serves the SPA shell, including for client-side routes"

  expect_json viewer "dashboard proxies /api to the API" "$WEB_URL/api/v1/fleet/summary" \
    '.hostCount >= $n' --argjson n "${#EXPECTED_HOSTS[@]}"
  expect_json none "dashboard proxies /actuator/health to the API" "$WEB_URL/actuator/health" '.status == "UP"'
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
  wait_until "dashboard to serve on ${WEB_URL}" web_serving
  check_identity
  check_fleet
  check_read_models
  check_dashboard_auth
  check_agent_auth
  check_decommission
  check_web
  # Last, because it is the slowest: the drifting host needs a full flip period.
  check_drift

  log "all ${PASSED} checks passed in ${SECONDS}s"
}

main "$@"
