# One EC2 instance running single-node k3s. Cloud-init installs a pinned k3s release,
# creates the enrollment-token Secret from SSM and drops a HelmChart manifest that the
# k3s Helm controller uses to install the Driftwatch chart from OCI.
#
# Access: no SSH and no key pair. Operators use SSM Session Manager. The only inbound
# rule is HTTP (Traefik ingress) from the fleet security group and var.admin_cidrs.

locals {
  # Traefik, k3s's bundled ingress controller, listens on the node's port 80.
  http_port = 80

  # Canonical's public SSM parameter for the current Ubuntu 24.04 LTS gp3 AMI.
  ami_parameter = "/aws/service/canonical/ubuntu/server/24.04/stable/current/${var.architecture}/hvm/ebs-gp3/ami-id"

  public_base_url = "http://${aws_eip.this.public_ip}"

  namespace_manifest = {
    apiVersion = "v1"
    kind       = "Namespace"
    metadata = {
      name = var.namespace
      labels = {
        "pod-security.kubernetes.io/enforce"         = "restricted"
        "pod-security.kubernetes.io/enforce-version" = "latest"
        "pod-security.kubernetes.io/warn"            = "restricted"
        "pod-security.kubernetes.io/audit"           = "restricted"
      }
    }
  }

  helm_chart_manifest = {
    apiVersion = "helm.cattle.io/v1"
    kind       = "HelmChart"
    metadata = {
      name      = "driftwatch"
      namespace = "kube-system"
    }
    spec = {
      chart           = var.chart_ref
      version         = var.chart_version
      targetNamespace = var.namespace
      createNamespace = false
      valuesContent   = yamlencode(var.chart_values)
    }
  }

  k3s_config = {
    "write-kubeconfig-mode" = "0600"
    "secrets-encryption"    = true
    "node-name"             = var.name
  }

  bootstrap_script = templatefile("${path.module}/templates/bootstrap.sh.tftpl", {
    k3s_version               = var.k3s_version
    k3s_install_script_url    = "https://raw.githubusercontent.com/k3s-io/k3s/${replace(var.k3s_version, "+", "%2B")}/install.sh"
    k3s_install_script_sha256 = var.k3s_install_script_sha256
    chart_ref                 = var.chart_ref
    chart_version             = var.chart_version
    namespace                 = var.namespace
    secret_name               = var.enrollment_secret_name
    secret_key                = var.enrollment_secret_key
  })

  user_data = join("\n", ["#cloud-config", yamlencode({
    hostname          = var.name
    preserve_hostname = false
    write_files = [
      {
        path        = "/usr/local/libexec/driftwatch/get-enrollment-token"
        permissions = "0700"
        owner       = "root:root"
        content     = module.instance_role.get_parameter_script
      },
      {
        path        = "/etc/rancher/k3s/config.yaml"
        permissions = "0600"
        owner       = "root:root"
        content     = yamlencode(local.k3s_config)
      },
      {
        path        = "/etc/driftwatch/namespace.yaml"
        permissions = "0600"
        owner       = "root:root"
        content     = yamlencode(local.namespace_manifest)
      },
      {
        path        = "/etc/driftwatch/helmchart.yaml"
        permissions = "0600"
        owner       = "root:root"
        content     = yamlencode(local.helm_chart_manifest)
      },
      {
        # Amazon Time Sync (link-local, not subject to security groups). SigV4 requests
        # to SSM fail if the clock is more than five minutes off.
        path        = "/etc/systemd/timesyncd.conf.d/50-amazon-time-sync.conf"
        permissions = "0644"
        owner       = "root:root"
        content     = "[Time]\nNTP=169.254.169.123\n"
      },
      {
        path        = "/usr/local/sbin/driftwatch-control-plane-bootstrap"
        permissions = "0700"
        owner       = "root:root"
        content     = local.bootstrap_script
      },
    ]
    runcmd = [["/usr/local/sbin/driftwatch-control-plane-bootstrap"]]
  })])
}

data "aws_ssm_parameter" "ami" {
  name = local.ami_parameter
}

module "instance_role" {
  source = "../ssm-instance-role"

  name          = "${var.name}-control-plane"
  parameter_arn = var.enrollment_token_parameter_arn
  kms_key_arn   = var.enrollment_token_kms_key_arn
  tags          = var.tags
}

resource "aws_security_group" "this" {
  name        = "${var.name}-control-plane"
  description = "Driftwatch control plane: HTTP ingress from the fleet and admin CIDRs only"
  vpc_id      = var.vpc_id

  tags = merge(var.tags, { Name = "${var.name}-control-plane" })

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_vpc_security_group_ingress_rule" "http_from_clients" {
  for_each = var.client_security_group_ids

  security_group_id            = aws_security_group.this.id
  description                  = "HTTP ingress (API and dashboard) from ${each.key}"
  ip_protocol                  = "tcp"
  from_port                    = local.http_port
  to_port                      = local.http_port
  referenced_security_group_id = each.value

  tags = merge(var.tags, { Name = "${var.name}-http-from-${each.key}" })
}

resource "aws_vpc_security_group_ingress_rule" "http_from_admins" {
  for_each = toset(var.admin_cidrs)

  security_group_id = aws_security_group.this.id
  description       = "HTTP ingress (dashboard) from admin CIDR ${each.value}"
  ip_protocol       = "tcp"
  from_port         = local.http_port
  to_port           = local.http_port
  cidr_ipv4         = each.value

  tags = merge(var.tags, { Name = "${var.name}-http-from-admin" })
}

#trivy:ignore:AVD-AWS-0104 HTTPS egress to the internet is required without a NAT gateway or VPC endpoints: GitHub (k3s), ghcr.io (chart and images) and the SSM/KMS APIs. Only TCP 443 is open.
resource "aws_vpc_security_group_egress_rule" "https" {
  security_group_id = aws_security_group.this.id
  description       = "HTTPS egress: k3s and chart downloads, image pulls, SSM"
  ip_protocol       = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_ipv4         = "0.0.0.0/0"

  tags = merge(var.tags, { Name = "${var.name}-https-egress" })
}

# Allocated before the instance so the public address can go into the chart values
# (OIDC issuer and redirect URIs) and stays stable across instance replacement.
resource "aws_eip" "this" {
  domain = "vpc"

  tags = merge(var.tags, { Name = "${var.name}-control-plane" })
}

resource "aws_instance" "this" {
  ami                    = data.aws_ssm_parameter.ami.insecure_value
  instance_type          = var.instance_type
  subnet_id              = var.subnet_id
  vpc_security_group_ids = [aws_security_group.this.id]
  iam_instance_profile   = module.instance_role.instance_profile_name
  ebs_optimized          = true
  monitoring             = false

  user_data                   = local.user_data
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

    tags = merge(var.tags, { Name = "${var.name}-control-plane-root" })
  }

  tags = merge(var.tags, {
    Name = "${var.name}-control-plane"
    Role = "control-plane"
  })

  lifecycle {
    # The SSM parameter tracks the latest AMI; don't replace the server on every release.
    # Taint or replace deliberately to pick up a new base image.
    ignore_changes = [ami]
  }
}

resource "aws_eip_association" "this" {
  allocation_id = aws_eip.this.allocation_id
  instance_id   = aws_instance.this.id
}
