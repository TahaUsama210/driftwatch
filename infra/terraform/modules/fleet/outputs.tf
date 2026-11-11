output "instance_ids" {
  description = "Instance IDs keyed by host name (the targets for SSM sessions)."
  value       = { for host, instance in aws_instance.this : host => instance.id }
}

output "hosts" {
  description = "Per-host details keyed by host name: distro, instance ID, private IP and AZ."
  value = {
    for host, instance in aws_instance.this : host => {
      distro            = local.hosts[host].distro
      instance_id       = instance.id
      private_ip        = instance.private_ip
      availability_zone = instance.availability_zone
    }
  }
}

output "security_group_id" {
  description = "ID of the fleet security group (allow it on the control plane's ingress)."
  value       = aws_security_group.this.id
}

output "role_name" {
  description = "Name of the fleet IAM role."
  value       = module.instance_role.role_name
}

output "iam_policies" {
  description = "Trust and inline policies of the fleet role, as objects."
  value       = module.instance_role.policies
}

output "user_data" {
  description = "Rendered cloud-init user data keyed by host name (contains no secrets)."
  value       = local.user_data
}

output "install_script" {
  description = "Rendered agent installer (identical on every host): download, sha256 verification, install."
  value       = local.install_script
}

output "systemd_unit" {
  description = "Rendered driftwatch-agent.service unit (identical on every host)."
  value       = local.unit_file
}
