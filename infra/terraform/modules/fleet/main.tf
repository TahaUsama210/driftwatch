# N EC2 instances spread across Ubuntu 24.04, Debian 12 and Amazon Linux 2023, each
# running the Driftwatch agent as a hardened systemd service.
#
# Boot sequence (cloud-init, see templates/):
#   1. install the SSM agent where the AMI lacks it (Debian)
#   2. download the agent archive and checksums.txt from the GitHub release, verify the
#      sha256 and refuse to install anything that does not match (fail closed)
#   3. enable driftwatch-agent.service; on every start it reads the enrollment token from
#      SSM into /run (tmpfs), so the token is never written to disk
#
# Access: no SSH, no key pair, no inbound rules at all. Operators use Session Manager.

locals {
  # Public SSM parameters maintained by Canonical, Debian and AWS (no Marketplace).
  ami_parameters = {
    "ubuntu-24.04" = "/aws/service/canonical/ubuntu/server/24.04/stable/current/${var.architecture}/hvm/ebs-gp3/ami-id"
    "debian-12"    = "/aws/service/debian/release/12/latest/${var.architecture}"
    "al2023"       = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-${var.architecture == "amd64" ? "x86_64" : "arm64"}"
  }

  short_names = {
    "ubuntu-24.04" = "ubuntu"
    "debian-12"    = "debian"
    "al2023"       = "al2023"
  }

  # Round-robin: host i gets distros[i % len] and subnets[i % len]. Keys are stable as
  # long as instance_count and distros don't change order.
  hosts = {
    for i in range(var.instance_count) :
    format("%s-%s-%02d", var.name, local.short_names[var.distros[i % length(var.distros)]], i + 1) => {
      distro    = var.distros[i % length(var.distros)]
      subnet_id = var.subnet_ids[i % length(var.subnet_ids)]
    }
  }

  unit_file = templatefile("${path.module}/templates/driftwatch-agent.service.tftpl", {
    server_url = var.server_url
    interval   = var.agent_interval
  })

  write_env_script = file("${path.module}/templates/write-agent-env.sh")

  install_script = templatefile("${path.module}/templates/install-agent.sh.tftpl", {
    agent_version    = var.agent_version
    release_base_url = var.agent_release_base_url
    checksums_sha256 = var.checksums_sha256 == null ? "" : var.checksums_sha256
  })

  user_data = {
    for host, cfg in local.hosts : host => join("\n", ["#cloud-config", yamlencode({
      hostname          = host
      preserve_hostname = false
      write_files = [
        {
          path        = "/usr/local/libexec/driftwatch/get-enrollment-token"
          permissions = "0700"
          owner       = "root:root"
          content     = module.instance_role.get_parameter_script
        },
        {
          path        = "/usr/local/libexec/driftwatch/write-agent-env"
          permissions = "0700"
          owner       = "root:root"
          content     = local.write_env_script
        },
        {
          path        = "/usr/local/sbin/driftwatch-install-agent"
          permissions = "0700"
          owner       = "root:root"
          content     = local.install_script
        },
        {
          path        = "/etc/systemd/system/driftwatch-agent.service"
          permissions = "0644"
          owner       = "root:root"
          content     = local.unit_file
        },
        {
          # Amazon Time Sync (link-local, not subject to security groups). SigV4 requests
          # to SSM fail if the clock is more than five minutes off.
          path        = "/etc/systemd/timesyncd.conf.d/50-amazon-time-sync.conf"
          permissions = "0644"
          owner       = "root:root"
          content     = "[Time]\nNTP=169.254.169.123\n"
        },
      ]
      runcmd = concat(
        [["sh", "-c", "systemctl try-restart systemd-timesyncd.service 2>/dev/null || true"]],
        cfg.distro == "debian-12" ? [["sh", "-c", local.debian_ssm_agent_install]] : [],
        [["/usr/local/sbin/driftwatch-install-agent"]],
      )
    })])
  }

  # Debian's official AMIs ship without the SSM agent; AWS publishes the package in a
  # regional S3 bucket (fetched over HTTPS). Ubuntu and AL2023 AMIs include it.
  debian_ssm_agent_install = join(" && ", [
    "set -eu",
    "curl --proto '=https' --tlsv1.2 -fsSL --retry 5 -o /tmp/amazon-ssm-agent.deb https://s3.${data.aws_region.current.region}.amazonaws.com/amazon-ssm-${data.aws_region.current.region}/latest/debian_${var.architecture}/amazon-ssm-agent.deb",
    "dpkg -i /tmp/amazon-ssm-agent.deb",
    "rm -f /tmp/amazon-ssm-agent.deb",
    "systemctl enable --now amazon-ssm-agent",
  ])
}

data "aws_region" "current" {}

data "aws_ssm_parameter" "ami" {
  for_each = toset(var.distros)

  name = local.ami_parameters[each.key]
}

module "instance_role" {
  source = "../ssm-instance-role"

  name          = "${var.name}-fleet"
  parameter_arn = var.enrollment_token_parameter_arn
  kms_key_arn   = var.enrollment_token_kms_key_arn
  tags          = var.tags
}

# No ingress rules at all: the agent only makes outbound connections.
resource "aws_security_group" "this" {
  name        = "${var.name}-fleet"
  description = "Driftwatch fleet: no inbound access, HTTPS out, HTTP to the VPC only"
  vpc_id      = var.vpc_id

  tags = merge(var.tags, { Name = "${var.name}-fleet" })

  lifecycle {
    create_before_destroy = true
  }
}

#trivy:ignore:AVD-AWS-0104 HTTPS egress to the internet is required without a NAT gateway or VPC endpoints: GitHub release downloads and the SSM/KMS APIs. Only TCP 443 is open.
resource "aws_vpc_security_group_egress_rule" "https" {
  security_group_id = aws_security_group.this.id
  description       = "HTTPS egress: agent release download, SSM"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = "0.0.0.0/0"

  tags = merge(var.tags, { Name = "${var.name}-fleet-https-egress" })
}

resource "aws_vpc_security_group_egress_rule" "http_to_vpc" {
  security_group_id = aws_security_group.this.id
  description       = "HTTP to the control plane inside the VPC"
  ip_protocol       = "tcp"
  from_port         = 80
  to_port           = 80
  cidr_ipv4         = var.vpc_cidr_block

  tags = merge(var.tags, { Name = "${var.name}-fleet-http-to-vpc" })
}

resource "aws_instance" "this" {
  for_each = local.hosts

  ami                    = data.aws_ssm_parameter.ami[each.value.distro].insecure_value
  instance_type          = var.instance_type
  subnet_id              = each.value.subnet_id
  vpc_security_group_ids = [aws_security_group.this.id]
  iam_instance_profile   = module.instance_role.instance_profile_name
  ebs_optimized          = true
  monitoring             = false

  user_data                   = local.user_data[each.key]
  user_data_replace_on_change = true

  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
    instance_metadata_tags      = "disabled"
  }

  root_block_device {
    volume_type           = "gp3"
    volume_size           = var.root_volume_size_gb
    encrypted             = true
    kms_key_id            = var.ebs_kms_key_arn
    delete_on_termination = true

    tags = merge(var.tags, { Name = "${each.key}-root" })
  }

  tags = merge(var.tags, {
    Name   = each.key
    Role   = "fleet"
    Distro = each.value.distro
  })

  lifecycle {
    # The SSM parameters track the latest AMIs; don't replace hosts on every release.
    ignore_changes = [ami]
  }
}
