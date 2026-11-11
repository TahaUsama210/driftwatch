output "dashboard_url" {
  description = "Dashboard URL. Reachable only from admin_cidrs; with none set, use the port-forward command and expect OIDC redirects to this address."
  value       = "${module.control_plane.public_base_url}/"
}

output "oidc_issuer" {
  description = "OIDC issuer the API accepts."
  value       = module.control_plane.oidc_issuer
}

output "control_plane_instance_id" {
  description = "Instance ID of the k3s control plane."
  value       = module.control_plane.instance_id
}

output "fleet_hosts" {
  description = "Fleet hosts keyed by host name: distro, instance ID, private IP and AZ."
  value       = module.fleet.hosts
}

output "ssm_session_commands" {
  description = "Shell sessions through SSM Session Manager (no SSH), keyed by host name."
  value = merge(
    { "${var.name}-control-plane" = "aws ssm start-session --region ${var.region} --target ${module.control_plane.instance_id}" },
    { for host, id in module.fleet.instance_ids : host => "aws ssm start-session --region ${var.region} --target ${id}" },
  )
}

output "dashboard_port_forward_command" {
  description = "Forwards the control plane's HTTP port to localhost:8080 through SSM (for API checks without opening admin_cidrs)."
  value       = "aws ssm start-session --region ${var.region} --target ${module.control_plane.instance_id} --document-name AWS-StartPortForwardingSession --parameters portNumber=80,localPortNumber=8080"
}

output "enrollment_token_parameter" {
  description = "Name of the SSM SecureString that holds the enrollment token."
  value       = aws_ssm_parameter.enrollment_token.name
}
