# Shared settings for up.sh, smoke.sh and down.sh. Sourced, not executed.
# shellcheck shell=bash
# The settings are read by the scripts that source this file.
# shellcheck disable=SC2034

readonly CLUSTER_NAME=driftwatch
# k3s v1.36.5+k3s1, released 2026-09-30.
readonly K3S_IMAGE=rancher/k3s:v1.36.5-k3s1
readonly API_PORT=36443
readonly INGRESS_PORT=38080
readonly CONTROL_PLANE_NAMESPACE=driftwatch
readonly AGENT_NAMESPACE=driftwatch-agent
readonly CONTROL_PLANE_RELEASE=driftwatch
readonly AGENT_RELEASE=driftwatch-agent

K3D="${K3D:-k3d}"
KUBECTL="${KUBECTL:-kubectl}"
HELM="${HELM:-helm}"
DOCKER="${DOCKER:-docker}"

K3D_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$K3D_DIR/../.." && pwd)"

# Git Bash: k3d.exe, helm.exe and docker.exe need Windows paths. Converting them
# here (instead of relying on MSYS's argument rewriting) keeps arguments such as
# "127.0.0.1:38080:80@loadbalancer" untouched.
if [[ "$(uname -s)" == MINGW* || "$(uname -s)" == MSYS* ]]; then
  export MSYS_NO_PATHCONV=1
  K3D_DIR="$(cd "$K3D_DIR" && pwd -W)"
  REPO_ROOT="$(cd "$REPO_ROOT" && pwd -W)"
fi
readonly K3D_DIR REPO_ROOT

log() { printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*" >&2; }
die() { log "ERROR: $*"; exit 1; }

# Usage: require K3D KUBECTL  (names of the variables above, not commands)
require() {
  local var
  for var in "$@"; do
    command -v "${!var}" >/dev/null 2>&1 || die "${!var} not found: add it to PATH or set $var to its path"
  done
}

cluster_exists() {
  "$K3D" cluster get "$CLUSTER_NAME" >/dev/null 2>&1
}

# A kubeconfig for this cluster only; the user's default kubeconfig and current
# context are never modified.
use_cluster_kubeconfig() {
  KUBECONFIG="$("$K3D" kubeconfig write "$CLUSTER_NAME")"
  export KUBECONFIG
}
