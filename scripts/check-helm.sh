#!/usr/bin/env bash
#
# Static checks for both Helm charts, exactly as CI runs them:
#
#   helm lint and helm unittest for each chart
#   helm template | kubeconform -strict against the Kubernetes schemas below
#   trivy config for each chart, failing on HIGH or CRITICAL findings
#
#   bash scripts/check-helm.sh
#
# Following the chart-testing convention, a chart is rendered once per
# <chart>/ci/*-values.yaml file, or with its default values when it has none (a chart
# whose defaults need a secret, such as an enrollment token, ships a ci values file).
# Only Docker is required.

set -euo pipefail
# shellcheck source=lib/tools.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/tools.sh"

readonly CHARTS=(deploy/helm/driftwatch deploy/helm/driftwatch-agent)
readonly KUBERNETES_VERSION="${KUBERNETES_VERSION:-1.36.6}"
# CRDs (the optional ServiceMonitor) are validated against the community catalog.
readonly CRD_SCHEMAS='https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'

helm() {
  run_tool --entrypoint helm -- "$HELM_IMAGE" "$@"
}

kubeconform() {
  run_tool --interactive -- "$KUBECONFORM_IMAGE" -strict -summary \
    -kubernetes-version "$KUBERNETES_VERSION" \
    -schema-location default -schema-location "$CRD_SCHEMAS" "$@"
}

for chart in "${CHARTS[@]}"; do
  log "helm lint: ${chart}"
  helm lint "$chart"

  log "helm unittest: ${chart}"
  helm unittest --strict "$chart"

  values_args=()
  for values in "$REPO_ROOT/$chart"/ci/*-values.yaml; do
    if [[ -e $values ]]; then
      values_args+=("--values=${chart}/ci/$(basename "$values")")
    fi
  done
  ((${#values_args[@]})) || values_args=("")
  for values in "${values_args[@]}"; do
    log "helm template | kubeconform: ${chart} ${values:-(default values)}"
    helm template driftwatch "$chart" ${values:+"$values"} | kubeconform -
  done
done

# One scan per chart, rendered with its ci/default-values.yaml when it has one. Trivy
# skips (with only a warning) a chart that fails to render, and the agent chart refuses
# to render without an enrollment token, so scanning deploy/helm as a whole would
# silently leave the agent chart, the privileged one, unscanned.
for chart in "${CHARTS[@]}"; do
  log "trivy config: ${chart}"
  trivy_args=()
  if [[ -e $REPO_ROOT/$chart/ci/default-values.yaml ]]; then
    trivy_args+=(--helm-values "${chart}/ci/default-values.yaml")
  fi
  bash "$REPO_ROOT/scripts/trivy.sh" config --exit-code 1 --severity HIGH,CRITICAL \
    ${trivy_args[@]+"${trivy_args[@]}"} "$chart"
done

log "all Helm checks passed"
