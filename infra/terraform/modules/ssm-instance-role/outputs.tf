output "role_name" {
  description = "Name of the IAM role."
  value       = aws_iam_role.this.name
}

output "role_arn" {
  description = "ARN of the IAM role."
  value       = aws_iam_role.this.arn
}

output "instance_profile_name" {
  description = "Name of the instance profile to attach to EC2 instances."
  value       = aws_iam_instance_profile.this.name
}

output "parameter_name" {
  description = "Name of the parameter the role may read, derived from parameter_arn."
  value       = local.parameter_name
}

output "get_parameter_script" {
  description = "Python 3 script (standard library only) that prints the decrypted parameter using the role's IMDSv2 credentials. Install it on the instance; it takes no arguments."
  value = templatefile("${path.module}/templates/get-parameter.py.tftpl", {
    region         = local.region
    parameter_name = local.parameter_name
  })
}

output "policies" {
  description = "The role's trust policy and inline policies as objects (for callers' tests and audits)."
  value = {
    assume_role      = jsondecode(aws_iam_role.this.assume_role_policy)
    session_manager  = jsondecode(aws_iam_role_policy.session_manager.policy)
    enrollment_token = jsondecode(aws_iam_role_policy.enrollment_token.policy)
  }
}
