output "bucket_name" {
  description = "Name of the state bucket."
  value       = aws_s3_bucket.state.bucket
}

output "kms_key_arn" {
  description = "ARN of the KMS key that encrypts state objects."
  value       = aws_kms_key.state.arn
}

output "backend_config" {
  description = "Contents for envs/demo/backend.hcl (terraform init -backend-config=backend.hcl)."
  value       = <<-EOT
    bucket       = "${aws_s3_bucket.state.bucket}"
    key          = "envs/demo/terraform.tfstate"
    region       = "${var.region}"
    encrypt      = true
    kms_key_id   = "${aws_kms_key.state.arn}"
    use_lockfile = true
  EOT
}
