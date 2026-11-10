#!/usr/bin/env bash
# Delete the local k3d cluster "driftwatch" and everything in it, including the
# Postgres volume. The driftwatch-*:dev images stay in Docker for the next up.sh.
#
#   deploy/k3d/down.sh
set -euo pipefail

# shellcheck source=lib.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

require K3D

if cluster_exists; then
  log "deleting cluster $CLUSTER_NAME"
  "$K3D" cluster delete "$CLUSTER_NAME"
else
  log "cluster $CLUSTER_NAME does not exist; nothing to do"
fi
