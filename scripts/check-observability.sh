#!/usr/bin/env bash
#
# Static checks for deploy/observability, exactly as CI runs them:
#
#   promtool check config and check rules, with files mounted where compose mounts them
#   promtool test rules (the alert unit tests)
#   amtool check-config
#   every Grafana dashboard is valid JSON with a uid and a title
#
#   bash scripts/check-observability.sh
#
# Requires Docker and jq.

set -euo pipefail
# shellcheck source=lib/tools.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/tools.sh"

readonly OBS=deploy/observability

command -v jq >/dev/null 2>&1 || { log "jq is required but not installed"; exit 1; }

promtool() {
  run_tool --entrypoint promtool \
    --volume "$(host_path "$REPO_ROOT/$OBS/prometheus/prometheus.yml"):/etc/prometheus/prometheus.yml:ro" \
    --volume "$(host_path "$REPO_ROOT/$OBS/prometheus/rules"):/etc/prometheus/rules:ro" \
    -- "$PROMETHEUS_IMAGE" "$@"
}

mapfile -t rule_files < <(cd "$REPO_ROOT/$OBS/prometheus/rules" && ls -- *.yml)
mapfile -t rule_tests < <(cd "$REPO_ROOT" && ls -- "$OBS"/prometheus/tests/*.yml)
mapfile -t dashboards < <(cd "$REPO_ROOT" && ls -- "$OBS"/grafana/dashboards/*.json)

log "promtool check config"
promtool check config /etc/prometheus/prometheus.yml

log "promtool check rules"
promtool check rules "${rule_files[@]/#//etc/prometheus/rules/}"

log "promtool test rules"
promtool test rules "${rule_tests[@]}"

log "amtool check-config"
run_tool --entrypoint amtool -- "$ALERTMANAGER_IMAGE" \
  check-config "$OBS/alertmanager/alertmanager.yml"

log "Grafana dashboards"
for dashboard in "${dashboards[@]}"; do
  jq -e '(.uid | type == "string" and length > 0) and (.title | type == "string" and length > 0)' \
    "$REPO_ROOT/$dashboard" >/dev/null || { log "invalid dashboard: ${dashboard}"; exit 1; }
  log "  ${dashboard}: $(jq -r '"\(.title) (uid \(.uid), \(.panels | length) panels)"' "$REPO_ROOT/$dashboard")"
done

log "all observability checks passed"
