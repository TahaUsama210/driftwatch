#!/usr/bin/env bash
#
# Static checks for infra/terraform, exactly as CI runs them. Never contacts AWS:
#
#   terraform fmt -check -recursive
#   terraform init -backend=false, validate and test (mock providers) in every root and module
#   tflint --recursive with the ruleset pinned in infra/terraform/.tflint.hcl
#   trivy config, failing on HIGH or CRITICAL findings
#
#   bash scripts/check-terraform.sh
#
# Only Docker is required. Set GITHUB_TOKEN to avoid GitHub API rate limits when tflint
# downloads its ruleset.

set -euo pipefail
# shellcheck source=lib/tools.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/tools.sh"

readonly TF_ROOT=infra/terraform
mkdir -p "$CACHE_DIR/terraform-plugins" "$CACHE_DIR/tflint-plugins"

# terraform DIR [args...]
# Each directory gets its own data directory in the cache instead of DIR/.terraform, so
# the check neither reads nor writes provider installs in the checkout (which may hold
# binaries for another OS from a local run).
terraform() {
  local dir=$1
  shift
  local data_dir="$CACHE_DIR/terraform-data/${dir//\//_}"
  mkdir -p "$data_dir"
  run_tool ${HOST_USER[@]+"${HOST_USER[@]}"} --env HOME=/tmp \
    --env TF_IN_AUTOMATION=1 --env TF_INPUT=0 \
    --env TF_PLUGIN_CACHE_DIR=/plugin-cache --env TF_DATA_DIR=/tf-data \
    --volume "$(host_path "$CACHE_DIR/terraform-plugins"):/plugin-cache" \
    --volume "$(host_path "$data_dir"):/tf-data" \
    --workdir "/repo/${dir}" -- "$TERRAFORM_IMAGE" "$@"
}

tflint() {
  run_tool ${HOST_USER[@]+"${HOST_USER[@]}"} --env HOME=/tmp \
    --env GITHUB_TOKEN --env TFLINT_PLUGIN_DIR=/plugin-cache \
    --volume "$(host_path "$CACHE_DIR/tflint-plugins"):/plugin-cache" \
    --workdir "/repo/${TF_ROOT}" -- "$TFLINT_IMAGE" --config "/repo/${TF_ROOT}/.tflint.hcl" "$@"
}

# Every directory with Terraform files is a root or a module, and each is checked on its own.
mapfile -t dirs < <(cd "$REPO_ROOT" && find "$TF_ROOT" -name '*.tf' -not -path '*/.terraform/*' \
  -exec dirname {} \; | sort -u)
((${#dirs[@]})) || { log "no Terraform configurations under ${TF_ROOT}"; exit 1; }

log "terraform fmt"
terraform "$TF_ROOT" fmt -check -recursive -diff

for dir in "${dirs[@]}"; do
  log "terraform init, validate and test: ${dir}"
  # The committed lock file must already cover every provider; CI never rewrites it.
  terraform "$dir" init -backend=false -lockfile=readonly
  terraform "$dir" validate
  terraform "$dir" test
done

log "tflint"
tflint --init
tflint --recursive --format compact

log "trivy config"
bash "$REPO_ROOT/scripts/trivy.sh" config --exit-code 1 --severity HIGH,CRITICAL \
  --skip-dirs '**/.terraform' "$TF_ROOT"

log "all Terraform checks passed"
