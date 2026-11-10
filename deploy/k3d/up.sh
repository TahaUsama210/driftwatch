#!/usr/bin/env bash
# Create the local k3d cluster "driftwatch", build and import the images, install
# both charts, and wait until everything is ready.
#
#   deploy/k3d/up.sh                 # from anywhere; idempotent (re-run to redeploy)
#   SKIP_BUILD=1 deploy/k3d/up.sh    # reuse the driftwatch-*:dev images already built
#
# Needs docker, k3d, kubectl and helm (override any of them with DOCKER, K3D, KUBECTL,
# HELM). Runs from Git Bash on Windows and on Linux CI runners. The cluster gets its
# own kubeconfig (printed at the end); the default kubeconfig is left alone.
set -euo pipefail

# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

require DOCKER K3D KUBECTL HELM

readonly CONTROL_PLANE_CHART="$REPO_ROOT/deploy/helm/driftwatch"
readonly AGENT_CHART="$REPO_ROOT/deploy/helm/driftwatch-agent"
readonly CONTROL_PLANE_VALUES="$K3D_DIR/values-driftwatch.yaml"
readonly AGENT_VALUES="$K3D_DIR/values-agent.yaml"
readonly LOCAL_IMAGES=(driftwatch-api:dev driftwatch-web:dev driftwatch-agent:dev)

create_cluster() {
  if cluster_exists; then
    log "cluster $CLUSTER_NAME exists; starting it if stopped"
    "$K3D" cluster start "$CLUSTER_NAME" --wait >/dev/null
    return
  fi
  log "creating cluster $CLUSTER_NAME ($K3S_IMAGE, 1 server + 2 agents)"
  "$K3D" cluster create "$CLUSTER_NAME" \
    --image "$K3S_IMAGE" \
    --servers 1 --agents 2 \
    --api-port "127.0.0.1:$API_PORT" \
    --port "127.0.0.1:$INGRESS_PORT:80@loadbalancer" \
    --kubeconfig-update-default=false \
    --kubeconfig-switch-context=false \
    --wait --timeout 300s
}

build_images() {
  if [[ "${SKIP_BUILD:-}" == 1 ]]; then
    log "SKIP_BUILD=1: using existing ${LOCAL_IMAGES[*]}"
    local image
    for image in "${LOCAL_IMAGES[@]}"; do
      "$DOCKER" image inspect "$image" >/dev/null 2>&1 || die "$image not found; build it or unset SKIP_BUILD"
    done
    return
  fi
  log "building images from $REPO_ROOT"
  "$DOCKER" build -t driftwatch-api:dev "$REPO_ROOT/api"
  "$DOCKER" build -t driftwatch-web:dev "$REPO_ROOT/web"
  "$DOCKER" build -t driftwatch-agent:dev "$REPO_ROOT/agent"
}

# Each image is imported under a tag derived from its content (dev-<image id>), and
# the charts are installed with that tag. A rebuilt image then changes the pod spec
# and rolls out; with the fixed :dev tag Helm would see no change and the pods would
# keep running the previous build.
image_tag() {
  local id
  id="$("$DOCKER" image inspect --format '{{.Id}}' "$1")"
  id="${id#sha256:}"
  printf 'dev-%s' "${id:0:12}"
}

tag_images() {
  API_TAG="$(image_tag driftwatch-api:dev)"
  WEB_TAG="$(image_tag driftwatch-web:dev)"
  AGENT_TAG="$(image_tag driftwatch-agent:dev)"
  "$DOCKER" tag driftwatch-api:dev "driftwatch-api:$API_TAG"
  "$DOCKER" tag driftwatch-web:dev "driftwatch-web:$WEB_TAG"
  "$DOCKER" tag driftwatch-agent:dev "driftwatch-agent:$AGENT_TAG"
}

# Only the locally built images are imported. The pinned upstream images
# (Keycloak, Postgres) are pulled by the nodes: `docker save` of a multi-platform
# image from Docker Desktop's containerd store omits the other platforms' blobs,
# which makes `k3d image import` fail.
import_images() {
  local images=("driftwatch-api:$API_TAG" "driftwatch-web:$WEB_TAG" "driftwatch-agent:$AGENT_TAG")
  log "importing ${images[*]} into the nodes"
  "$K3D" image import --cluster "$CLUSTER_NAME" --mode direct "${images[@]}"
}

# Namespaces are created here rather than by the charts so their Pod Security
# labels exist before any pod is admitted. The control plane enforces "restricted";
# the agent namespace must be "privileged" for its hostPath mounts, and holds
# nothing else.
apply_namespaces() {
  log "applying namespaces with Pod Security labels"
  "$KUBECTL" apply -f - <<EOF
apiVersion: v1
kind: Namespace
metadata:
  name: $CONTROL_PLANE_NAMESPACE
  labels:
    pod-security.kubernetes.io/enforce: restricted
    pod-security.kubernetes.io/enforce-version: latest
    pod-security.kubernetes.io/warn: restricted
    pod-security.kubernetes.io/audit: restricted
---
apiVersion: v1
kind: Namespace
metadata:
  name: $AGENT_NAMESPACE
  labels:
    pod-security.kubernetes.io/enforce: privileged
    pod-security.kubernetes.io/enforce-version: latest
EOF
}

install_control_plane() {
  log "installing chart $CONTROL_PLANE_RELEASE into $CONTROL_PLANE_NAMESPACE"
  "$HELM" upgrade --install "$CONTROL_PLANE_RELEASE" "$CONTROL_PLANE_CHART" \
    --namespace "$CONTROL_PLANE_NAMESPACE" \
    -f "$CONTROL_PLANE_VALUES" \
    --set "api.image.tag=$API_TAG" --set "web.image.tag=$WEB_TAG" \
    --wait --timeout 10m
}

# The agents need the control plane's (generated) enrollment token. It is copied
# Secret to Secret as base64, so it never appears in plaintext, in Helm values or
# on a command line.
copy_enrollment_token() {
  local token_b64
  token_b64="$("$KUBECTL" -n "$CONTROL_PLANE_NAMESPACE" get secret "$CONTROL_PLANE_RELEASE-enrollment" \
    -o jsonpath='{.data.enrollment-token}')"
  [[ -n "$token_b64" ]] || die "secret $CONTROL_PLANE_RELEASE-enrollment has no enrollment-token"
  log "copying the enrollment token into $AGENT_NAMESPACE/driftwatch-enrollment"
  "$KUBECTL" apply -f - <<EOF >/dev/null
apiVersion: v1
kind: Secret
metadata:
  name: driftwatch-enrollment
  namespace: $AGENT_NAMESPACE
  labels:
    app.kubernetes.io/part-of: driftwatch
type: Opaque
data:
  enrollment-token: $token_b64
EOF
}

install_agents() {
  log "installing chart $AGENT_RELEASE into $AGENT_NAMESPACE"
  "$HELM" upgrade --install "$AGENT_RELEASE" "$AGENT_CHART" \
    --namespace "$AGENT_NAMESPACE" \
    -f "$AGENT_VALUES" \
    --set "image.tag=$AGENT_TAG" \
    --wait --timeout 5m
}

wait_for_rollouts() {
  log "waiting for rollouts"
  local workload
  for workload in deployment/driftwatch-api deployment/driftwatch-web deployment/driftwatch-keycloak \
      statefulset/driftwatch-postgresql; do
    "$KUBECTL" -n "$CONTROL_PLANE_NAMESPACE" rollout status "$workload" --timeout 5m
  done
  "$KUBECTL" -n "$AGENT_NAMESPACE" rollout status daemonset/driftwatch-agent --timeout 5m
}

create_cluster
use_cluster_kubeconfig
build_images
tag_images
import_images
apply_namespaces
install_control_plane
copy_enrollment_token
install_agents
wait_for_rollouts

"$KUBECTL" get pods -A -l app.kubernetes.io/part-of=driftwatch -o wide
log "ready: dashboard at http://localhost:$INGRESS_PORT/"
log "kubeconfig: export KUBECONFIG='$KUBECONFIG'"
log "next: deploy/k3d/smoke.sh"
