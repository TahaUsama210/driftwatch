# Unit tests for the control-plane module. The AWS provider is mocked: nothing is created.

mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = { account_id = "123456789012" }
  }
  mock_data "aws_region" {
    defaults = { region = "eu-west-1" }
  }
  mock_data "aws_partition" {
    defaults = { partition = "aws" }
  }
  mock_data "aws_ssm_parameter" {
    defaults = { insecure_value = "ami-0123456789abcdef0" }
  }
  mock_resource "aws_eip" {
    defaults = {
      public_ip     = "203.0.113.10"
      allocation_id = "eipalloc-0123456789abcdef0"
    }
  }
  mock_resource "aws_security_group" {
    defaults = { id = "sg-0aaaaaaaaaaaaaaaa" }
  }
  # key_name is optional+computed; AWS reports "" when no key pair is configured. A
  # key_name set in the configuration overrides this default and fails the assertion.
  mock_resource "aws_instance" {
    defaults = { key_name = "" }
  }
}

variables {
  name                           = "driftwatch-test"
  vpc_id                         = "vpc-0123456789abcdef0"
  subnet_id                      = "subnet-0123456789abcdef0"
  chart_version                  = "0.2.0"
  enrollment_token_parameter_arn = "arn:aws:ssm:eu-west-1:123456789012:parameter/driftwatch/test/enrollment-token"
  enrollment_token_kms_key_arn   = "arn:aws:kms:eu-west-1:123456789012:key/0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
  chart_values                   = { publicBaseUrl = "http://203.0.113.10" }
}

run "no_ingress_by_default" {
  command = apply

  assert {
    condition     = length(aws_vpc_security_group_ingress_rule.http_from_clients) == 0 && length(aws_vpc_security_group_ingress_rule.http_from_admins) == 0
    error_message = "With no clients and empty admin_cidrs the control plane must accept no inbound traffic."
  }

  assert {
    condition     = length(aws_security_group.this.ingress) == 0
    error_message = "Inline ingress rules must not be used."
  }
}

run "ingress_only_from_fleet_sg_and_admin_cidrs" {
  command = apply

  variables {
    client_security_group_ids = { fleet = "sg-0bbbbbbbbbbbbbbbb" }
    admin_cidrs               = ["203.0.113.7/32", "198.51.100.0/24"]
  }

  assert {
    condition     = [for r in values(aws_vpc_security_group_ingress_rule.http_from_clients) : r.referenced_security_group_id] == ["sg-0bbbbbbbbbbbbbbbb"]
    error_message = "The fleet security group must be the only security-group source."
  }

  assert {
    condition     = toset([for r in values(aws_vpc_security_group_ingress_rule.http_from_admins) : r.cidr_ipv4]) == toset(["203.0.113.7/32", "198.51.100.0/24"])
    error_message = "The CIDR sources must be exactly admin_cidrs."
  }

  assert {
    condition = alltrue([
      for r in concat(values(aws_vpc_security_group_ingress_rule.http_from_clients), values(aws_vpc_security_group_ingress_rule.http_from_admins)) :
      r.ip_protocol == "tcp" && r.from_port == 80 && r.to_port == 80
    ])
    error_message = "Ingress must be TCP 80 (the HTTP ingress port) only."
  }

  assert {
    condition = alltrue([
      for r in concat(values(aws_vpc_security_group_ingress_rule.http_from_clients), values(aws_vpc_security_group_ingress_rule.http_from_admins)) :
      !(r.from_port <= 22 && r.to_port >= 22)
    ])
    error_message = "No ingress rule may cover port 22 (SSH)."
  }

  assert {
    condition = alltrue([
      for r in values(aws_vpc_security_group_ingress_rule.http_from_admins) : r.cidr_ipv4 != "0.0.0.0/0" && r.cidr_ipv6 == null
    ])
    error_message = "No ingress rule may allow 0.0.0.0/0 or IPv6."
  }
}

run "egress_is_https_only" {
  command = apply

  assert {
    condition     = aws_vpc_security_group_egress_rule.https.from_port == 443 && aws_vpc_security_group_egress_rule.https.to_port == 443 && aws_vpc_security_group_egress_rule.https.ip_protocol == "tcp"
    error_message = "The only egress rule must be TCP 443."
  }
}

run "instance_hardening" {
  command = apply

  assert {
    condition     = aws_instance.this.metadata_options[0].http_tokens == "required"
    error_message = "IMDSv2 must be required."
  }

  assert {
    condition     = aws_instance.this.metadata_options[0].http_put_response_hop_limit == 1
    error_message = "The IMDS hop limit must be 1 so pods cannot reach instance credentials."
  }

  assert {
    condition     = aws_instance.this.root_block_device[0].encrypted && aws_instance.this.root_block_device[0].volume_type == "gp3"
    error_message = "The root volume must be encrypted gp3."
  }

  assert {
    condition     = aws_instance.this.key_name == null || aws_instance.this.key_name == ""
    error_message = "No EC2 key pair may be attached (access is through SSM)."
  }

  # Static guard: no key pair resource and no key_name argument anywhere in the module.
  assert {
    condition = aws_instance.this.id != "" && alltrue([
      for f in fileset(".", "*.tf") : !can(regex("resource\\s+\"aws_key_pair\"|(?m)^\\s*key_name\\s*=", file(f)))
    ])
    error_message = "The module must not declare aws_key_pair resources or set key_name."
  }

  assert {
    condition     = aws_instance.this.iam_instance_profile == "driftwatch-test-control-plane"
    error_message = "The instance must use the module's least-privilege SSM instance profile."
  }

  assert {
    condition     = aws_instance.this.ami == "ami-0123456789abcdef0"
    error_message = "The AMI must come from the public SSM parameter."
  }

  assert {
    condition     = data.aws_ssm_parameter.ami.name == "/aws/service/canonical/ubuntu/server/24.04/stable/current/amd64/hvm/ebs-gp3/ami-id"
    error_message = "The AMI must be Canonical's Ubuntu 24.04 public parameter (no Marketplace subscription)."
  }

  assert {
    condition     = aws_eip_association.this.allocation_id == aws_eip.this.allocation_id
    error_message = "The Elastic IP must be associated with the instance."
  }
}

run "iam_reads_only_the_token_parameter" {
  command = apply

  assert {
    condition     = output.iam_policies.enrollment_token.Statement[0].Resource == [var.enrollment_token_parameter_arn]
    error_message = "The control plane may read only the enrollment-token parameter."
  }

  assert {
    condition     = output.iam_policies.enrollment_token.Statement[1].Resource == [var.enrollment_token_kms_key_arn]
    error_message = "The control plane may decrypt only with the token's KMS key."
  }
}

run "user_data_pins_and_verifies_k3s" {
  command = apply

  assert {
    condition     = startswith(output.user_data, "#cloud-config\n")
    error_message = "User data must be a cloud-config document."
  }

  assert {
    condition = strcontains(
      one([for f in yamldecode(output.user_data).write_files : f.content if f.path == "/usr/local/sbin/driftwatch-control-plane-bootstrap"]),
      "echo '46177d4c99440b4c0311b67233823a8e8a2fc09693f6c89af1a7161e152fbfad  '\"$work/install.sh\" | sha256sum -c --strict -"
    )
    error_message = "The k3s install script must be verified against the pinned sha256 before it runs."
  }

  assert {
    condition = strcontains(
      one([for f in yamldecode(output.user_data).write_files : f.content if f.path == "/usr/local/sbin/driftwatch-control-plane-bootstrap"]),
      "INSTALL_K3S_VERSION='v1.36.5+k3s1'"
    )
    error_message = "k3s must be installed at the pinned version."
  }

  assert {
    condition = strcontains(
      one([for f in yamldecode(output.user_data).write_files : f.content if f.path == "/usr/local/sbin/driftwatch-control-plane-bootstrap"]),
      "https://raw.githubusercontent.com/k3s-io/k3s/v1.36.5%2Bk3s1/install.sh"
    )
    error_message = "The install script must come from the pinned k3s tag."
  }
}

run "user_data_installs_chart_from_oci" {
  command = apply

  assert {
    condition = yamldecode(one([for f in yamldecode(output.user_data).write_files : f.content if f.path == "/etc/driftwatch/helmchart.yaml"])).spec == {
      chart           = "oci://ghcr.io/tahausama210/charts/driftwatch"
      version         = "0.2.0"
      targetNamespace = "driftwatch"
      createNamespace = false
      valuesContent   = yamlencode({ publicBaseUrl = "http://203.0.113.10" })
    }
    error_message = "The HelmChart must install the OCI chart at chart_version with chart_values."
  }

  assert {
    condition     = yamldecode(one([for f in yamldecode(output.user_data).write_files : f.content if f.path == "/etc/driftwatch/namespace.yaml"])).metadata.labels["pod-security.kubernetes.io/enforce"] == "restricted"
    error_message = "The control-plane namespace must enforce Pod Security restricted."
  }

  assert {
    condition     = yamldecode(yamldecode(output.user_data).write_files[1].content)["secrets-encryption"] == true
    error_message = "k3s must encrypt Secrets at rest."
  }

  assert {
    condition     = output.oidc_issuer == "http://203.0.113.10/auth/realms/driftwatch"
    error_message = "The issuer must be http://<control plane address>/auth/realms/driftwatch."
  }
}

run "arm64_uses_arm_ami" {
  command = apply

  variables {
    instance_type = "t4g.large"
    architecture  = "arm64"
  }

  assert {
    condition     = data.aws_ssm_parameter.ami.name == "/aws/service/canonical/ubuntu/server/24.04/stable/current/arm64/hvm/ebs-gp3/ami-id"
    error_message = "arm64 must select the arm64 Ubuntu AMI."
  }
}

run "rejects_open_admin_cidr" {
  command = plan

  variables {
    admin_cidrs = ["0.0.0.0/0"]
  }

  expect_failures = [var.admin_cidrs]
}

run "rejects_wide_admin_cidr" {
  command = plan

  variables {
    admin_cidrs = ["0.0.0.0/4"]
  }

  expect_failures = [var.admin_cidrs]
}

run "rejects_invalid_admin_cidr" {
  command = plan

  variables {
    admin_cidrs = ["203.0.113.7"]
  }

  expect_failures = [var.admin_cidrs]
}

run "rejects_floating_chart_version" {
  command = plan

  variables {
    chart_version = "latest"
  }

  expect_failures = [var.chart_version]
}

run "rejects_k3s_channel" {
  command = plan

  variables {
    k3s_version = "stable"
  }

  expect_failures = [var.k3s_version]
}

run "rejects_architecture_mismatch" {
  command = plan

  variables {
    instance_type = "t4g.large"
  }

  expect_failures = [var.architecture]
}

run "rejects_bad_install_script_sha" {
  command = plan

  variables {
    k3s_install_script_sha256 = "not-a-sha"
  }

  expect_failures = [var.k3s_install_script_sha256]
}

run "rejects_tiny_root_volume" {
  command = plan

  variables {
    root_volume_size_gb = 8
  }

  expect_failures = [var.root_volume_size_gb]
}
