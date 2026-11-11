# An EC2 instance role that can do exactly two things:
#   1. register with Systems Manager and open Session Manager channels (no SSH needed)
#   2. read one SecureString parameter, decrypting it with one KMS key, only through SSM
#
# It deliberately does NOT attach the AWS managed policy AmazonSSMManagedInstanceCore,
# which also grants ssm:GetParameter(s) on every parameter in the account, plus Run
# Command and State Manager permissions this demo does not use. The Session Manager
# statement follows AWS's guidance for a custom Session Manager instance profile:
# https://docs.aws.amazon.com/systems-manager/latest/userguide/getting-started-create-iam-instance-profile.html

data "aws_partition" "current" {}
data "aws_region" "current" {}
data "aws_caller_identity" "current" {}

locals {
  partition  = data.aws_partition.current.partition
  region     = data.aws_region.current.region
  account_id = data.aws_caller_identity.current.account_id

  # A hierarchical parameter "/a/b" has the ARN suffix "parameter/a/b" (leading slash
  # dropped); a flat parameter "a" has "parameter/a".
  parameter_path = split(":parameter/", var.parameter_arn)[1]
  parameter_name = strcontains(local.parameter_path, "/") ? "/${local.parameter_path}" : local.parameter_path

  # Policies are built with jsonencode (not aws_iam_policy_document) so tests can decode
  # and assert them under a mocked provider.
  assume_role_policy = {
    Version = "2012-10-17"
    Statement = [{
      Sid       = "EC2AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action    = "sts:AssumeRole"
      Condition = { StringEquals = { "aws:SourceAccount" = local.account_id } }
    }]
  }

  session_manager_policy = {
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "RegisterManagedInstance"
        Effect   = "Allow"
        Action   = ["ssm:UpdateInstanceInformation"]
        Resource = ["arn:${local.partition}:ec2:${local.region}:${local.account_id}:instance/*"]
      },
      {
        # The ssmmessages actions support no resource-level permissions, so "*" is the only
        # valid resource. They open the Session Manager control and data channels.
        Sid    = "SessionManagerChannels"
        Effect = "Allow"
        Action = [
          "ssmmessages:CreateControlChannel",
          "ssmmessages:CreateDataChannel",
          "ssmmessages:OpenControlChannel",
          "ssmmessages:OpenDataChannel",
        ]
        Resource = ["*"]
      },
    ]
  }

  enrollment_token_policy = {
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "ReadEnrollmentToken"
        Effect   = "Allow"
        Action   = ["ssm:GetParameter"]
        Resource = [var.parameter_arn]
      },
      {
        # SSM encrypts SecureStrings with the parameter ARN as encryption context, so the
        # key can only be used to decrypt this parameter, and only through SSM.
        Sid      = "DecryptEnrollmentTokenViaSsm"
        Effect   = "Allow"
        Action   = ["kms:Decrypt"]
        Resource = [var.kms_key_arn]
        Condition = {
          StringEquals = {
            "kms:ViaService"                      = "ssm.${local.region}.amazonaws.com"
            "kms:EncryptionContext:PARAMETER_ARN" = var.parameter_arn
          }
        }
      },
    ]
  }
}

resource "aws_iam_role" "this" {
  name                 = var.name
  description          = "Driftwatch instance role: Session Manager plus one SSM parameter."
  assume_role_policy   = jsonencode(local.assume_role_policy)
  max_session_duration = 3600

  tags = merge(var.tags, { Name = var.name })
}

#trivy:ignore:AVD-AWS-0057 The ssmmessages channel actions support no resource-level permissions, so Resource must be "*". The four actions are listed explicitly, with no action wildcards.
resource "aws_iam_role_policy" "session_manager" {
  name   = "session-manager"
  role   = aws_iam_role.this.id
  policy = jsonencode(local.session_manager_policy)
}

resource "aws_iam_role_policy" "enrollment_token" {
  name   = "read-enrollment-token"
  role   = aws_iam_role.this.id
  policy = jsonencode(local.enrollment_token_policy)
}

resource "aws_iam_instance_profile" "this" {
  name = var.name
  role = aws_iam_role.this.name

  tags = merge(var.tags, { Name = var.name })
}
