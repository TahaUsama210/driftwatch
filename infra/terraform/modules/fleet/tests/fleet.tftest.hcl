# Unit tests for the fleet module. The AWS provider is mocked: nothing is created.

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
  # key_name is optional+computed; AWS reports "" when no key pair is configured. A
  # key_name set in the configuration overrides this default and fails the assertion.
  mock_resource "aws_instance" {
    defaults = { key_name = "" }
  }
  mock_resource "aws_security_group" {
    defaults = { id = "sg-0bbbbbbbbbbbbbbbb" }
  }

  override_data {
    target = data.aws_ssm_parameter.ami["ubuntu-24.04"]
    values = { insecure_value = "ami-0000000000000ubu" }
  }
  override_data {
    target = data.aws_ssm_parameter.ami["debian-12"]
    values = { insecure_value = "ami-0000000000000deb" }
  }
  override_data {
    target = data.aws_ssm_parameter.ami["al2023"]
    values = { insecure_value = "ami-00000000000al2023" }
  }
}

variables {
  name                           = "driftwatch-test"
  vpc_id                         = "vpc-0123456789abcdef0"
  vpc_cidr_block                 = "10.42.0.0/16"
  subnet_ids                     = ["subnet-0aaaaaaaaaaaaaaaa", "subnet-0bbbbbbbbbbbbbbbb"]
  server_url                     = "http://10.42.0.10"
  agent_version                  = "0.2.0"
  enrollment_token_parameter_arn = "arn:aws:ssm:eu-west-1:123456789012:parameter/driftwatch/test/enrollment-token"
  enrollment_token_kms_key_arn   = "arn:aws:kms:eu-west-1:123456789012:key/0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
}

run "default_fleet_is_one_host_per_distro" {
  command = apply

  assert {
    condition     = length(aws_instance.this) == 3
    error_message = "The default fleet must have three instances."
  }

  assert {
    condition     = toset([for i in values(aws_instance.this) : i.tags["Distro"]]) == toset(["ubuntu-24.04", "debian-12", "al2023"])
    error_message = "The default fleet must cover Ubuntu 24.04, Debian 12 and Amazon Linux 2023."
  }

  assert {
    condition     = toset(keys(aws_instance.this)) == toset(["driftwatch-test-ubuntu-01", "driftwatch-test-debian-02", "driftwatch-test-al2023-03"])
    error_message = "Hosts must be named <name>-<distro>-<index>."
  }

  assert {
    condition = (
      aws_instance.this["driftwatch-test-ubuntu-01"].ami == "ami-0000000000000ubu" &&
      aws_instance.this["driftwatch-test-debian-02"].ami == "ami-0000000000000deb" &&
      aws_instance.this["driftwatch-test-al2023-03"].ami == "ami-00000000000al2023"
    )
    error_message = "Each host must boot the AMI of its distribution."
  }

  assert {
    condition = (
      data.aws_ssm_parameter.ami["ubuntu-24.04"].name == "/aws/service/canonical/ubuntu/server/24.04/stable/current/amd64/hvm/ebs-gp3/ami-id" &&
      data.aws_ssm_parameter.ami["debian-12"].name == "/aws/service/debian/release/12/latest/amd64" &&
      data.aws_ssm_parameter.ami["al2023"].name == "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64"
    )
    error_message = "AMIs must come from the vendors' public SSM parameters (no Marketplace)."
  }

  assert {
    condition     = toset([for i in values(aws_instance.this) : i.subnet_id]) == toset(var.subnet_ids)
    error_message = "Hosts must be spread across all subnets."
  }
}

run "larger_fleet_spreads_round_robin" {
  command = apply

  variables {
    instance_count = 7
  }

  assert {
    condition     = length(aws_instance.this) == 7
    error_message = "instance_count = 7 must create seven instances."
  }

  assert {
    condition = (
      length([for i in values(aws_instance.this) : i if i.tags["Distro"] == "ubuntu-24.04"]) == 3 &&
      length([for i in values(aws_instance.this) : i if i.tags["Distro"] == "debian-12"]) == 2 &&
      length([for i in values(aws_instance.this) : i if i.tags["Distro"] == "al2023"]) == 2
    )
    error_message = "Seven hosts over three distros must split 3/2/2."
  }

  assert {
    condition = (
      length([for i in values(aws_instance.this) : i if i.subnet_id == "subnet-0aaaaaaaaaaaaaaaa"]) == 4 &&
      length([for i in values(aws_instance.this) : i if i.subnet_id == "subnet-0bbbbbbbbbbbbbbbb"]) == 3
    )
    error_message = "Seven hosts over two subnets must split 4/3."
  }
}

run "single_distro_fleet" {
  command = apply

  variables {
    instance_count = 2
    distros        = ["al2023"]
  }

  assert {
    condition     = length(data.aws_ssm_parameter.ami) == 1 && alltrue([for i in values(aws_instance.this) : i.tags["Distro"] == "al2023"])
    error_message = "A single-distro fleet must only look up and use that distro's AMI."
  }
}

run "no_inbound_access" {
  command = apply

  assert {
    condition     = length(aws_security_group.this.ingress) == 0
    error_message = "The fleet security group must have no ingress rules."
  }

  # Static guard: the module declares no ingress rule resources at all.
  assert {
    condition = aws_security_group.this.id != "" && alltrue([
      for f in fileset(".", "*.tf") : !can(regex("resource\\s+\"(aws_vpc_security_group_ingress_rule|aws_security_group_rule|aws_key_pair)\"|(?m)^\\s*key_name\\s*=", file(f)))
    ])
    error_message = "The fleet module must not declare ingress rules, key pairs or key_name."
  }

  assert {
    condition     = alltrue([for i in values(aws_instance.this) : i.key_name == null || i.key_name == ""])
    error_message = "No EC2 key pair may be attached (access is through SSM)."
  }
}

run "egress_is_https_and_vpc_http_only" {
  command = apply

  assert {
    condition     = aws_vpc_security_group_egress_rule.https.from_port == 443 && aws_vpc_security_group_egress_rule.https.to_port == 443
    error_message = "Internet egress must be TCP 443 only."
  }

  assert {
    condition     = aws_vpc_security_group_egress_rule.http_to_vpc.cidr_ipv4 == "10.42.0.0/16" && aws_vpc_security_group_egress_rule.http_to_vpc.from_port == 80
    error_message = "Plain HTTP may only go to the VPC (the control plane)."
  }
}

run "instance_hardening" {
  command = apply

  assert {
    condition     = alltrue([for i in values(aws_instance.this) : i.metadata_options[0].http_tokens == "required" && i.metadata_options[0].http_put_response_hop_limit == 1])
    error_message = "IMDSv2 must be required on every host."
  }

  assert {
    condition     = alltrue([for i in values(aws_instance.this) : i.root_block_device[0].encrypted && i.root_block_device[0].volume_type == "gp3"])
    error_message = "Every root volume must be encrypted gp3."
  }

  assert {
    condition     = alltrue([for i in values(aws_instance.this) : i.iam_instance_profile == "driftwatch-test-fleet"])
    error_message = "Every host must use the fleet's least-privilege instance profile."
  }
}

run "iam_reads_only_the_token_parameter" {
  command = apply

  assert {
    condition = [for s in output.iam_policies.enrollment_token.Statement : { action = s.Action, resource = s.Resource }] == [
      { action = ["ssm:GetParameter"], resource = [var.enrollment_token_parameter_arn] },
      { action = ["kms:Decrypt"], resource = [var.enrollment_token_kms_key_arn] },
    ]
    error_message = "The fleet may read only the enrollment-token parameter and decrypt only with its key."
  }

  assert {
    condition     = length(output.iam_policies.session_manager.Statement) == 2
    error_message = "The Session Manager policy must stay minimal."
  }
}

run "every_host_embeds_the_installer_unit_and_token_reader" {
  command = apply

  assert {
    condition = alltrue([
      for host, ud in output.user_data : (
        one([for f in yamldecode(ud).write_files : f.content if f.path == "/usr/local/sbin/driftwatch-install-agent"]) == output.install_script &&
        one([for f in yamldecode(ud).write_files : f.content if f.path == "/etc/systemd/system/driftwatch-agent.service"]) == output.systemd_unit &&
        one([for f in yamldecode(ud).write_files : f.permissions if f.path == "/usr/local/libexec/driftwatch/get-enrollment-token"]) == "0700"
      )
    ])
    error_message = "Every host's cloud-init must install the shared installer, unit and token reader."
  }

  assert {
    condition     = alltrue([for host, ud in output.user_data : yamldecode(ud).runcmd[length(yamldecode(ud).runcmd) - 1] == ["/usr/local/sbin/driftwatch-install-agent"]])
    error_message = "cloud-init must run the installer as its last step."
  }
}

run "installer_verifies_checksums_and_fails_closed" {
  command = apply

  assert {
    condition     = startswith(output.install_script, "#!/bin/sh") && strcontains(output.install_script, "\nset -eu\n")
    error_message = "The installer must run with set -eu so any failed step aborts the install."
  }

  assert {
    condition     = strcontains(output.install_script, "base='https://github.com/tahausama210/driftwatch/releases/download/v0.2.0'")
    error_message = "The agent must come from the v<agent_version> GitHub release."
  }

  assert {
    condition     = strcontains(output.install_script, "archive=\"driftwatch-agent_$${version}_linux_$${arch}.tar.gz\"")
    error_message = "The archive name must follow the contract: driftwatch-agent_<version>_linux_<arch>.tar.gz."
  }

  assert {
    condition     = strcontains(output.install_script, "fetch \"$base/checksums.txt\"")
    error_message = "checksums.txt must be downloaded from the same release."
  }

  assert {
    condition     = strcontains(output.install_script, "| sha256sum -c --strict -) \\\n  || fail \"sha256 mismatch for $archive; refusing to install\"")
    error_message = "The archive's sha256 must be verified, and a mismatch must abort before installing."
  }

  assert {
    condition     = strcontains(output.install_script, "|| fail \"checksums.txt has no unique entry for $archive\"")
    error_message = "A missing or duplicated checksum entry must abort the install."
  }

  assert {
    # Verification precedes extraction, which precedes installation.
    condition = (
      length(split("sha256sum -c --strict -)", output.install_script)[0]) < length(split("tar -xzf", output.install_script)[0]) &&
      length(split("tar -xzf", output.install_script)[0]) < length(split("install -o root", output.install_script)[0])
    )
    error_message = "The checksum must be verified before the archive is extracted or installed."
  }

  assert {
    condition     = strcontains(output.install_script, "curl --proto '=https' --tlsv1.2")
    error_message = "Downloads must be HTTPS-only (no redirect to plain HTTP)."
  }

  assert {
    condition     = strcontains(output.install_script, "pinned_checksums_sha256=''")
    error_message = "Without checksums_sha256 the pin must be empty (checksums.txt alone is trusted)."
  }
}

run "pinned_checksums_file" {
  command = apply

  variables {
    checksums_sha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  }

  assert {
    condition     = strcontains(output.install_script, "pinned_checksums_sha256='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'")
    error_message = "checksums_sha256 must be passed to the installer."
  }

  assert {
    condition     = strcontains(output.install_script, "|| fail \"checksums.txt does not match the sha256 pinned in Terraform\"")
    error_message = "A checksums.txt that does not match the pin must abort the install."
  }
}

run "token_is_read_from_ssm_at_start_and_kept_off_disk" {
  command = apply

  assert {
    condition = alltrue([
      for host, ud in output.user_data : strcontains(
        one([for f in yamldecode(ud).write_files : f.content if f.path == "/usr/local/libexec/driftwatch/get-enrollment-token"]),
        "PARAMETER_NAME = \"/driftwatch/test/enrollment-token\""
      )
    ])
    error_message = "Hosts must read the enrollment token from the SSM parameter."
  }

  assert {
    condition = alltrue([
      for host, ud in output.user_data : strcontains(
        one([for f in yamldecode(ud).write_files : f.content if f.path == "/usr/local/libexec/driftwatch/write-agent-env"]),
        "dir=/run/driftwatch-agent"
      )
    ])
    error_message = "The token must be written only under /run (tmpfs)."
  }

  assert {
    condition     = strcontains(output.systemd_unit, "\nEnvironmentFile=-/run/driftwatch-agent/agent.env\n") && !strcontains(output.systemd_unit, "Environment=DRIFTWATCH_ENROLLMENT_TOKEN")
    error_message = "The unit must load the token from /run at start, never inline it."
  }
}

run "systemd_unit_is_hardened" {
  command = apply

  assert {
    # Whole-line matches, so a directive mentioned in a comment does not count.
    condition = alltrue([
      for directive in [
        "DynamicUser=yes",
        "AmbientCapabilities=CAP_DAC_READ_SEARCH",
        "CapabilityBoundingSet=CAP_DAC_READ_SEARCH",
        "NoNewPrivileges=yes",
        "ProtectSystem=strict",
        "ProtectHome=yes",
        "PrivateTmp=yes",
        "PrivateDevices=yes",
        "ProtectKernelTunables=yes",
        "ProtectKernelModules=yes",
        "ProtectControlGroups=yes",
        "RestrictNamespaces=yes",
        "RestrictSUIDSGID=yes",
        "MemoryDenyWriteExecute=yes",
        "SystemCallFilter=@system-service",
        "StateDirectory=driftwatch",
        "ExecStartPre=+/usr/local/libexec/driftwatch/write-agent-env",
        "Environment=DRIFTWATCH_SERVER_URL=http://10.42.0.10",
      ] : strcontains(output.systemd_unit, "\n${directive}\n")
    ])
    error_message = "The agent unit must carry every hardening directive."
  }

  assert {
    condition     = !can(regex("(?m)^(User=root|PrivateUsers=|ProcSubset=|PrivateNetwork=|CapabilityBoundingSet=$)", output.systemd_unit))
    error_message = "Directives that break the shadow, ownership or sysctl checks must not be set."
  }
}

run "only_debian_installs_the_ssm_agent" {
  command = apply

  assert {
    condition     = strcontains(jsonencode(yamldecode(output.user_data["driftwatch-test-debian-02"]).runcmd), "amazon-ssm-eu-west-1/latest/debian_amd64/amazon-ssm-agent.deb")
    error_message = "Debian hosts must install the SSM agent from AWS's regional bucket."
  }

  assert {
    condition = (
      !strcontains(output.user_data["driftwatch-test-ubuntu-01"], "amazon-ssm-agent.deb") &&
      !strcontains(output.user_data["driftwatch-test-al2023-03"], "amazon-ssm-agent.deb")
    )
    error_message = "Ubuntu and AL2023 AMIs already include the SSM agent."
  }

  assert {
    condition     = yamldecode(output.user_data["driftwatch-test-debian-02"]).hostname == "driftwatch-test-debian-02"
    error_message = "Each host's hostname must be its fleet name (what the dashboard shows)."
  }
}

run "arm64_fleet" {
  command = apply

  variables {
    instance_type = "t4g.micro"
    architecture  = "arm64"
  }

  assert {
    condition = (
      data.aws_ssm_parameter.ami["ubuntu-24.04"].name == "/aws/service/canonical/ubuntu/server/24.04/stable/current/arm64/hvm/ebs-gp3/ami-id" &&
      data.aws_ssm_parameter.ami["debian-12"].name == "/aws/service/debian/release/12/latest/arm64" &&
      data.aws_ssm_parameter.ami["al2023"].name == "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
    )
    error_message = "arm64 must select the arm64 AMIs."
  }
}

run "rejects_zero_instances" {
  command = plan

  variables {
    instance_count = 0
  }

  expect_failures = [var.instance_count]
}

run "rejects_unsupported_distro" {
  command = plan

  variables {
    distros = ["ubuntu-24.04", "centos-7"]
  }

  expect_failures = [var.distros]
}

run "rejects_duplicate_distros" {
  command = plan

  variables {
    distros = ["al2023", "al2023"]
  }

  expect_failures = [var.distros]
}

run "rejects_v_prefixed_agent_version" {
  command = plan

  variables {
    agent_version = "v0.2.0"
  }

  expect_failures = [var.agent_version]
}

run "rejects_plain_http_release_url" {
  command = plan

  variables {
    agent_release_base_url = "http://github.com/tahausama210/driftwatch/releases/download"
  }

  expect_failures = [var.agent_release_base_url]
}

run "rejects_malformed_checksums_pin" {
  command = plan

  variables {
    checksums_sha256 = "ABC"
  }

  expect_failures = [var.checksums_sha256]
}

run "rejects_too_short_interval" {
  command = plan

  variables {
    agent_interval = "5s"
  }

  expect_failures = [var.agent_interval]
}

run "rejects_architecture_mismatch" {
  command = plan

  variables {
    instance_type = "t4g.micro"
  }

  expect_failures = [var.architecture]
}
