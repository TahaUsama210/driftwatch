#!/usr/bin/env bash
#
# Runs the pinned Trivy with the repository mounted at /repo, so paths are given
# relative to the repository root:
#
#   bash scripts/trivy.sh config --exit-code 1 --severity HIGH,CRITICAL infra/terraform
#   bash scripts/trivy.sh image --ignore-unfixed --severity HIGH,CRITICAL driftwatch-api:ci
#
# The Docker socket is mounted so `image` can scan images in the local daemon. The
# vulnerability databases and checks bundle come from mirror.gcr.io, which does not
# rate-limit CI the way ghcr.io does, and are cached between runs. Exceptions live in
# .trivyignore.yaml at the repository root, each with a statement saying why.

set -euo pipefail
# shellcheck source=lib/tools.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/tools.sh"

mkdir -p "$CACHE_DIR/trivy"
run_tool \
  --volume /var/run/docker.sock:/var/run/docker.sock \
  --volume "$(host_path "$CACHE_DIR/trivy"):/root/.cache/trivy" \
  --env TRIVY_DB_REPOSITORY=mirror.gcr.io/aquasec/trivy-db:2 \
  --env TRIVY_JAVA_DB_REPOSITORY=mirror.gcr.io/aquasec/trivy-java-db:1 \
  --env TRIVY_CHECKS_BUNDLE_REPOSITORY=mirror.gcr.io/aquasec/trivy-checks:1 \
  --env TRIVY_IGNOREFILE=/repo/.trivyignore.yaml \
  --env TRIVY_NO_PROGRESS=true \
  -- "$TRIVY_IMAGE" "$@"
