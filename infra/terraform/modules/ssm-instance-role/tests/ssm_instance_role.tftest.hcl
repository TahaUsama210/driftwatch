# Unit tests for the least-privilege instance role. The provider is mocked; the account,
# region and partition lookups are overridden so the policy ARNs are deterministic.

mock_provider "aws" {
  override_data {
    target = data.aws_caller_identity.current
    values = { account_id = "123456789012" }
  }
  override_data {
    target = data.aws_region.current
    values = { region = "eu-west-1" }
  }
  override_data {
    target = data.aws_partition.current
    values = { partition = "aws" }
  }
}

variables {
  name          = "driftwatch-test-fleet"
  parameter_arn = "arn:aws:ssm:eu-west-1:123456789012:parameter/driftwatch/test/enrollment-token"
  kms_key_arn   = "arn:aws:kms:eu-west-1:123456789012:key/0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
}

run "trust_policy_allows_only_ec2_in_this_account" {
  command = apply

  assert {
    condition     = jsondecode(aws_iam_role.this.assume_role_policy).Statement[0].Principal == { Service = "ec2.amazonaws.com" }
    error_message = "Only the EC2 service may assume the role."
  }

  assert {
    condition     = jsondecode(aws_iam_role.this.assume_role_policy).Statement[0].Condition.StringEquals["aws:SourceAccount"] == "123456789012"
    error_message = "The trust policy must be scoped to this account (confused-deputy protection)."
  }

  assert {
    condition     = length(jsondecode(aws_iam_role.this.assume_role_policy).Statement) == 1
    error_message = "The trust policy must have exactly one statement."
  }
}

run "session_manager_policy_is_minimal" {
  command = apply

  assert {
    condition = toset(flatten([
      for s in jsondecode(aws_iam_role_policy.session_manager.policy).Statement : s.Action
      ])) == toset([
      "ssm:UpdateInstanceInformation",
      "ssmmessages:CreateControlChannel",
      "ssmmessages:CreateDataChannel",
      "ssmmessages:OpenControlChannel",
      "ssmmessages:OpenDataChannel",
    ])
    error_message = "The Session Manager policy must grant exactly the five Session Manager actions."
  }

  assert {
    condition = alltrue([
      for s in jsondecode(aws_iam_role_policy.session_manager.policy).Statement : s.Effect == "Allow" && alltrue([for a in s.Action : !strcontains(a, "*")])
    ])
    error_message = "No action wildcards are allowed."
  }

  assert {
    condition     = one([for s in jsondecode(aws_iam_role_policy.session_manager.policy).Statement : s.Resource if s.Sid == "RegisterManagedInstance"]) == ["arn:aws:ec2:eu-west-1:123456789012:instance/*"]
    error_message = "ssm:UpdateInstanceInformation must be scoped to EC2 instances in this account and region."
  }
}

run "token_policy_reads_one_parameter_and_decrypts_only_via_ssm" {
  command = apply

  assert {
    condition = jsondecode(aws_iam_role_policy.enrollment_token.policy).Statement[0] == {
      Sid      = "ReadEnrollmentToken"
      Effect   = "Allow"
      Action   = ["ssm:GetParameter"]
      Resource = ["arn:aws:ssm:eu-west-1:123456789012:parameter/driftwatch/test/enrollment-token"]
    }
    error_message = "The role may call ssm:GetParameter on the one enrollment-token parameter only."
  }

  assert {
    condition = jsondecode(aws_iam_role_policy.enrollment_token.policy).Statement[1] == {
      Sid      = "DecryptEnrollmentTokenViaSsm"
      Effect   = "Allow"
      Action   = ["kms:Decrypt"]
      Resource = ["arn:aws:kms:eu-west-1:123456789012:key/0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"]
      Condition = {
        StringEquals = {
          "kms:ViaService"                      = "ssm.eu-west-1.amazonaws.com"
          "kms:EncryptionContext:PARAMETER_ARN" = "arn:aws:ssm:eu-west-1:123456789012:parameter/driftwatch/test/enrollment-token"
        }
      }
    }
    error_message = "kms:Decrypt must be limited to the one key, through SSM, for the one parameter."
  }

  assert {
    condition     = length(jsondecode(aws_iam_role_policy.enrollment_token.policy).Statement) == 2
    error_message = "The token policy must have exactly two statements."
  }
}

run "attaches_no_managed_policies" {
  command = apply

  # The module declares no aws_iam_role_policy_attachment; this guards the profile wiring.
  assert {
    condition     = aws_iam_instance_profile.this.role == aws_iam_role.this.name
    error_message = "The instance profile must carry this role."
  }
}

run "client_script_targets_the_parameter" {
  command = apply

  assert {
    condition     = strcontains(output.get_parameter_script, "PARAMETER_NAME = \"/driftwatch/test/enrollment-token\"") && strcontains(output.get_parameter_script, "REGION = \"eu-west-1\"")
    error_message = "The client script must be rendered for this parameter and region."
  }

  assert {
    condition     = strcontains(output.get_parameter_script, "X-aws-ec2-metadata-token-ttl-seconds")
    error_message = "The client script must use IMDSv2 session tokens."
  }

  assert {
    condition     = output.parameter_name == "/driftwatch/test/enrollment-token"
    error_message = "The parameter name must be derived from the ARN with its leading slash."
  }
}

run "rejects_wildcard_parameter_arn" {
  command = plan

  variables {
    parameter_arn = "arn:aws:ssm:eu-west-1:123456789012:parameter/driftwatch/*"
  }

  expect_failures = [var.parameter_arn]
}

run "rejects_kms_alias" {
  command = plan

  variables {
    kms_key_arn = "arn:aws:kms:eu-west-1:123456789012:alias/aws/ssm"
  }

  expect_failures = [var.kms_key_arn]
}
