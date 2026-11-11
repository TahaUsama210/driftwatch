# shellcheck shell=bash
# Pinned tool images for the check scripts. Sourced, not executed.
#
# Every check runs its tool from one of these images, so CI, `make` and a laptop with
# only Docker run the same tool versions. Tags are for humans; the digests decide.
# The Prometheus and Alertmanager tags match deploy/observability/compose.yml, so
# promtool and amtool validate against the versions that run the configuration.

# shellcheck disable=SC2034 # the variables are used by the scripts that source this file
readonly TRIVY_IMAGE=aquasec/trivy:0.76.1@sha256:790fd66d1697395d8693d1acaa1ea702406a20f109b6fc7bac8db623eca6c06c
readonly TERRAFORM_IMAGE=hashicorp/terraform:1.16.7@sha256:c9c77f5b9820832cbc7b88d16df406ac6e75fee5a9077e5437db0c54e5ff33e3
readonly TFLINT_IMAGE=ghcr.io/terraform-linters/tflint:v0.65.0@sha256:2788f4d4fc663c0e953b011b01bf443fe5bfce7587ee916e588e6057b85d6359
# Helm 4.3.0 with the helm-unittest 1.2.1 plugin preinstalled.
readonly HELM_IMAGE=helmunittest/helm-unittest:4.3.0-1.2.1@sha256:28ea86c3e0b07f501f0c2e9c08b9f41e240f4a4b571cb5106b36cf0f0607e989
readonly KUBECONFORM_IMAGE=ghcr.io/yannh/kubeconform:v0.8.0@sha256:faffaf43f95aa6425306e1ab8d6fcad72acb9049158f38e574c085ea1ec0f64e
readonly PROMETHEUS_IMAGE=prom/prometheus:v3.15.1@sha256:fa3302279710be2f21a8301ec17a8b4f2301fd829bd113839b3b6b02b6f191a2
readonly ALERTMANAGER_IMAGE=prom/alertmanager:v0.34.1@sha256:e9733bafb1bdef9b00e25a21f8f99dc26a22224bf16641ad754d1649f4c3357a

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
readonly REPO_ROOT

# Git Bash would rewrite container paths such as /repo into Windows paths; Docker
# needs them verbatim, and the host side of a bind mount in Windows form.
export MSYS_NO_PATHCONV=1
host_path() {
  if [[ -d $1 ]]; then
    (cd "$1" && { pwd -W 2>/dev/null || pwd; })
  else
    printf '%s/%s\n' "$(host_path "$(dirname "$1")")" "$(basename "$1")"
  fi
}

# Cache shared across runs (provider and plugin downloads), kept outside the repo.
CACHE_DIR="${DRIFTWATCH_CACHE_DIR:-${XDG_CACHE_HOME:-$HOME/.cache}/driftwatch}"
mkdir -p "$CACHE_DIR"
readonly CACHE_DIR

# On Linux, run as the invoking user so files written into the checkout (for example
# .terraform/) are not owned by root. Docker Desktop maps ownership itself.
HOST_USER=()
if [[ $(uname -s) == Linux ]]; then
  HOST_USER=(--user "$(id -u):$(id -g)")
fi

log() { printf '%s [%s] %s\n' "$(date -u +%H:%M:%S)" "$(basename "$0" .sh)" "$*" >&2; }

# run_tool [docker run options...] -- IMAGE [args...]
# Runs IMAGE with the repository mounted at /repo (working directory /repo unless an
# option says otherwise).
run_tool() {
  local options=()
  while (($#)) && [[ $1 != -- ]]; do
    options+=("$1")
    shift
  done
  shift
  docker run --rm --volume "$(host_path "$REPO_ROOT"):/repo" --workdir /repo \
    ${options[@]+"${options[@]}"} "$@"
}
