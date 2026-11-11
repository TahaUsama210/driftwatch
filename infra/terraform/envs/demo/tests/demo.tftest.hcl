# Integration tests for the demo root: all modules wired together under a mocked AWS
# provider. The random provider is real (it runs locally) and generates the ephemeral
# enrollment token.

mock_provider "aws" {
  mock_resource "aws_cloudwatch_log_group" {
    defaults = { arn = "arn:aws:logs:eu-west-1:123456789012:log-group:/aws/vpc-flow-logs/test" }
  }
  mock_resource "aws_iam_role" {
    defaults = { arn = "arn:aws:iam::123456789012:role/test-vpc-flow-logs" }
  }
  mock_data "aws_caller_identity" {
    defaults = { account_id = "123456789012" }
  }
  mock_data "aws_region" {
    defaults = { region = "eu-west-1" }
  }
  mock_data "aws_partition" {
    defaults = { partition = "aws" }
  }
  mock_data "aws_availability_zones" {
    defaults = { names = ["eu-west-1a", "eu-west-1b", "eu-west-1c"] }
  }
  mock_data "aws_ssm_parameter" {
    defaults = { insecure_value = "ami-0123456789abcdef0" }
  }

  # IDs and ARNs shaped like the real ones, so the modules' input validation passes.
  mock_resource "aws_vpc" {
    defaults = { id = "vpc-0123456789abcdef0", cidr_block = "10.42.0.0/16" }
  }
  mock_resource "aws_subnet" {
    defaults = { id = "subnet-0123456789abcdef0" }
  }
  mock_resource "aws_security_group" {
    defaults = { id = "sg-0123456789abcdef0" }
  }
  mock_resource "aws_kms_key" {
    defaults = {
      arn    = "arn:aws:kms:eu-west-1:123456789012:key/0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
      key_id = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
    }
  }
  mock_resource "aws_ssm_parameter" {
    defaults = { arn = "arn:aws:ssm:eu-west-1:123456789012:parameter/driftwatch-demo/enrollment-token" }
  }
  mock_resource "aws_eip" {
    defaults = { public_ip = "203.0.113.10", allocation_id = "eipalloc-0123456789abcdef0" }
  }
  mock_resource "aws_instance" {
    defaults = { private_ip = "10.42.0.10", key_name = "" }
  }
}

variables {
  agent_version = "0.2.0"
  chart_version = "0.2.0"
}

run "token_is_a_kms_encrypted_write_only_secure_string" {
  command = apply

  assert {
    condition     = aws_ssm_parameter.enrollment_token.type == "SecureString" && aws_ssm_parameter.enrollment_token.key_id == aws_kms_key.enrollment.arn
    error_message = "The enrollment token must be a SecureString encrypted with the dedicated KMS key."
  }

  assert {
    condition     = aws_ssm_parameter.enrollment_token.value_wo == null && aws_ssm_parameter.enrollment_token.value_wo_version == 1
    error_message = "The token must be set write-only (value_wo): it is never persisted in state."
  }

  assert {
    condition     = aws_ssm_parameter.enrollment_token.name == "/driftwatch-demo/enrollment-token"
    error_message = "The parameter must be named /<name>/enrollment-token."
  }

  assert {
    condition     = aws_kms_key.enrollment.enable_key_rotation
    error_message = "The KMS key must rotate."
  }
}

run "wires_modules_together" {
  command = apply

  assert {
    condition     = output.oidc_issuer == "http://203.0.113.10/auth/realms/driftwatch"
    error_message = "The issuer must be http://<control plane address>/auth/realms/driftwatch."
  }

  assert {
    condition     = output.dashboard_url == "http://203.0.113.10/"
    error_message = "The dashboard URL must be the control plane's public address."
  }

  assert {
    condition = yamldecode(yamldecode(one([
      for f in yamldecode(module.control_plane.user_data).write_files : f.content if f.path == "/etc/driftwatch/helmchart.yaml"
      ])).spec.valuesContent) == {
      publicUrl  = "http://203.0.113.10"
      enrollment = { existingSecret = "driftwatch-enrollment", existingSecretKey = "enrollment-token" }
    }
    error_message = "The chart must get publicUrl and the cloud-init-created enrollment Secret, and no inline secrets."
  }

  assert {
    condition     = length(module.fleet.hosts) == 3 && toset([for h in values(module.fleet.hosts) : h.distro]) == toset(["ubuntu-24.04", "debian-12", "al2023"])
    error_message = "The demo fleet must have one host per supported distro."
  }

  assert {
    condition     = alltrue([for ud in values(module.fleet.user_data) : strcontains(ud, "Environment=DRIFTWATCH_SERVER_URL=http://10.42.0.10")])
    error_message = "Agents must report to the control plane's private address."
  }

  assert {
    condition     = output.ssm_session_commands["driftwatch-demo-control-plane"] == "aws ssm start-session --region eu-west-1 --target ${module.control_plane.instance_id}"
    error_message = "SSM session commands must be emitted for the control plane."
  }

  assert {
    condition     = length(output.ssm_session_commands) == 4
    error_message = "There must be one SSM session command per instance."
  }
}

run "both_roles_read_only_the_one_parameter" {
  command = apply

  assert {
    condition = alltrue([
      for p in [module.control_plane.iam_policies, module.fleet.iam_policies] :
      p.enrollment_token.Statement[0].Resource == [aws_ssm_parameter.enrollment_token.arn] &&
      p.enrollment_token.Statement[1].Resource == [aws_kms_key.enrollment.arn]
    ])
    error_message = "Both roles must be scoped to the enrollment-token parameter and its key."
  }
}

run "control_plane_admits_only_the_fleet_by_default" {
  command = apply

  assert {
    condition     = jsonencode(module.control_plane.ingress_sources) == jsonencode({ cidrs = [], security_group_ids = [module.fleet.security_group_id] })
    error_message = "By default only the fleet security group may reach the control plane."
  }
}

run "admin_cidrs_open_the_dashboard_to_those_addresses_only" {
  command = apply

  variables {
    admin_cidrs = ["203.0.113.7/32"]
  }

  assert {
    condition     = jsonencode(module.control_plane.ingress_sources.cidrs) == jsonencode(["203.0.113.7/32"])
    error_message = "admin_cidrs must be passed through to the control plane unchanged."
  }
}

run "rotating_the_token_bumps_the_write_only_version" {
  command = apply

  variables {
    enrollment_token_version = 2
  }

  assert {
    condition     = aws_ssm_parameter.enrollment_token.value_wo_version == 2
    error_message = "enrollment_token_version must drive value_wo_version."
  }
}

run "rejects_open_admin_cidr" {
  command = plan

  variables {
    admin_cidrs = ["0.0.0.0/0"]
  }

  expect_failures = [var.admin_cidrs]
}

run "rejects_bad_region" {
  command = plan

  variables {
    region = "Ireland"
  }

  expect_failures = [var.region]
}

run "rejects_missing_chart_version_format" {
  command = plan

  variables {
    chart_version = "main"
  }

  expect_failures = [var.chart_version]
}
