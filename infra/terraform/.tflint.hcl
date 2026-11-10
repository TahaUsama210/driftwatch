# Shared tflint configuration. Run from infra/terraform:
#   tflint --init --config "$PWD/.tflint.hcl"
#   tflint --recursive --config "$PWD/.tflint.hcl"
config {
  # Lint local module calls with the values the caller passes.
  call_module_type = "local"
}

plugin "terraform" {
  enabled = true
  preset  = "all"
}

plugin "aws" {
  enabled = true
  version = "0.50.0"
  source  = "github.com/terraform-linters/tflint-ruleset-aws"
  # deep_check calls AWS APIs; there are no credentials in CI by design.
  deep_check = false
}
