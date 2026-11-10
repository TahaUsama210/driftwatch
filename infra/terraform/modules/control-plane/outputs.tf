output "instance_id" {
  description = "ID of the control-plane instance (the target for SSM sessions)."
  value       = aws_instance.this.id
}

output "private_ip" {
  description = "Private IPv4 address. Fleet agents use it as their server URL."
  value       = aws_instance.this.private_ip
}

output "public_ip" {
  description = "Elastic IP of the control plane. Reachable only from admin_cidrs."
  value       = aws_eip.this.public_ip
}

output "public_base_url" {
  description = "Public base URL of the dashboard, e.g. http://203.0.113.10."
  value       = local.public_base_url
}

output "oidc_issuer" {
  description = "OIDC issuer the API must accept: <public base URL>/auth/realms/driftwatch."
  value       = "${local.public_base_url}/auth/realms/driftwatch"
}

output "http_port" {
  description = "TCP port of the HTTP ingress (Traefik)."
  value       = local.http_port
}

output "security_group_id" {
  description = "ID of the control-plane security group."
  value       = aws_security_group.this.id
}

output "role_name" {
  description = "Name of the control-plane IAM role."
  value       = module.instance_role.role_name
}

output "user_data" {
  description = "Rendered cloud-init user data (contains no secrets: the token is read from SSM at boot)."
  value       = local.user_data
}

output "iam_policies" {
  description = "Trust and inline policies of the control-plane role, as objects."
  value       = module.instance_role.policies
}

output "ingress_sources" {
  description = "Everything allowed to reach the HTTP ingress port: security group IDs and IPv4 CIDRs."
  value = {
    security_group_ids = sort([for r in values(aws_vpc_security_group_ingress_rule.http_from_clients) : r.referenced_security_group_id])
    cidrs              = sort([for r in values(aws_vpc_security_group_ingress_rule.http_from_admins) : r.cidr_ipv4])
  }
}
