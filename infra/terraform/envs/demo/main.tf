# Driftwatch demo environment: a VPC, a single-node k3s control plane running the chart,
# and a small mixed-distro fleet of agents reporting to it over the VPC.
#
#   internet --(admin_cidrs only, HTTP 80)--> control plane (EIP) <--(HTTP 80, fleet SG)-- fleet
#   operators --(SSM Session Manager)--> any instance        (no SSH, no key pairs)

locals {
  # The chart's keys (deploy/helm/driftwatch/values.yaml): publicUrl fixes the OIDC
  # issuer; the enrollment Secret is created by cloud-init from SSM.
  enrollment_secret_name = "driftwatch-enrollment"
  enrollment_secret_key  = "enrollment-token"
}

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

# --- Enrollment token: generated here, stored only in SSM ------------------------------

resource "aws_kms_key" "enrollment" {
  description             = "${var.name}: encrypts the Driftwatch enrollment-token SecureString"
  enable_key_rotation     = true
  deletion_window_in_days = 7

  # Default-style key policy: the account administers the key through IAM. The instance
  # roles get kms:Decrypt via their own policies, limited to SSM and this parameter.
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "AccountAdministersKeyThroughIam"
      Effect    = "Allow"
      Principal = { AWS = "arn:${data.aws_partition.current.partition}:iam::${data.aws_caller_identity.current.account_id}:root" }
      Action    = "kms:*"
      Resource  = "*"
    }]
  })
}

resource "aws_kms_alias" "enrollment" {
  name          = "alias/${var.name}-enrollment-token"
  target_key_id = aws_kms_key.enrollment.key_id
}

# Ephemeral: the value exists only during plan/apply and is never written to state or
# plan files. value_wo sends it to SSM; bump enrollment_token_version to rotate.
ephemeral "random_password" "enrollment" {
  length  = 48
  special = false
}

resource "aws_ssm_parameter" "enrollment_token" {
  name             = "/${var.name}/enrollment-token"
  description      = "Driftwatch enrollment token (agents and API). Rotate with enrollment_token_version."
  type             = "SecureString"
  tier             = "Standard"
  key_id           = aws_kms_key.enrollment.arn
  value_wo         = ephemeral.random_password.enrollment.result
  value_wo_version = var.enrollment_token_version
}

# --- Infrastructure ---------------------------------------------------------------------

module "network" {
  source = "../../modules/network"

  name       = var.name
  cidr_block = var.vpc_cidr_block
}

module "control_plane" {
  source = "../../modules/control-plane"

  name          = var.name
  vpc_id        = module.network.vpc_id
  subnet_id     = module.network.public_subnet_ids[0]
  instance_type = var.control_plane_instance_type
  architecture  = var.architecture

  chart_version = var.chart_version
  chart_values = {
    publicUrl = module.control_plane.public_base_url
    enrollment = {
      existingSecret    = local.enrollment_secret_name
      existingSecretKey = local.enrollment_secret_key
    }
  }
  enrollment_secret_name = local.enrollment_secret_name
  enrollment_secret_key  = local.enrollment_secret_key

  enrollment_token_parameter_arn = aws_ssm_parameter.enrollment_token.arn
  enrollment_token_kms_key_arn   = aws_kms_key.enrollment.arn

  client_security_group_ids = { fleet = module.fleet.security_group_id }
  admin_cidrs               = var.admin_cidrs
}

module "fleet" {
  source = "../../modules/fleet"

  name           = var.name
  vpc_id         = module.network.vpc_id
  vpc_cidr_block = module.network.vpc_cidr_block
  subnet_ids     = module.network.public_subnet_ids
  instance_count = var.fleet_instance_count
  instance_type  = var.fleet_instance_type
  architecture   = var.architecture

  # Agents reach the API through the control plane's Traefik ingress, inside the VPC.
  server_url       = "http://${module.control_plane.private_ip}"
  agent_version    = var.agent_version
  checksums_sha256 = var.agent_checksums_sha256

  enrollment_token_parameter_arn = aws_ssm_parameter.enrollment_token.arn
  enrollment_token_kms_key_arn   = aws_kms_key.enrollment.arn
}
