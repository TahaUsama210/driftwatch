# Unit tests for the state bucket. The AWS provider is mocked: nothing is created.

mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = { account_id = "123456789012" }
  }
  mock_data "aws_partition" {
    defaults = { partition = "aws" }
  }
  mock_resource "aws_kms_key" {
    defaults = {
      arn    = "arn:aws:kms:eu-west-1:123456789012:key/0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
      key_id = "0f1e2d3c-4b5a-6978-8796-a5b4c3d2e1f0"
    }
  }
}

run "bucket_name_is_account_and_region_scoped" {
  command = apply

  assert {
    condition     = aws_s3_bucket.state.bucket == "driftwatch-tfstate-123456789012-eu-west-1"
    error_message = "The bucket name must be <prefix>-<account>-<region>."
  }

  assert {
    condition     = aws_s3_bucket.state.force_destroy == false
    error_message = "The state bucket must never be force-destroyed."
  }
}

run "versioning_and_kms_encryption" {
  command = apply

  assert {
    condition     = aws_s3_bucket_versioning.state.versioning_configuration[0].status == "Enabled"
    error_message = "Versioning must be enabled."
  }

  assert {
    condition = alltrue([
      for r in aws_s3_bucket_server_side_encryption_configuration.state.rule :
      one(r.apply_server_side_encryption_by_default).sse_algorithm == "aws:kms" &&
      one(r.apply_server_side_encryption_by_default).kms_master_key_id == aws_kms_key.state.arn &&
      r.bucket_key_enabled
    ])
    error_message = "Objects must be encrypted with the customer-managed KMS key."
  }

  assert {
    condition     = aws_kms_key.state.enable_key_rotation
    error_message = "The state key must rotate."
  }
}

run "public_access_fully_blocked" {
  command = apply

  assert {
    condition = (
      aws_s3_bucket_public_access_block.state.block_public_acls &&
      aws_s3_bucket_public_access_block.state.block_public_policy &&
      aws_s3_bucket_public_access_block.state.ignore_public_acls &&
      aws_s3_bucket_public_access_block.state.restrict_public_buckets
    )
    error_message = "All four public access block settings must be on."
  }

  assert {
    condition     = aws_s3_bucket_ownership_controls.state.rule[0].object_ownership == "BucketOwnerEnforced"
    error_message = "ACLs must be disabled (BucketOwnerEnforced)."
  }
}

run "bucket_policy_requires_tls" {
  command = apply

  assert {
    condition = one([for s in jsondecode(aws_s3_bucket_policy.state.policy).Statement : s if s.Sid == "DenyInsecureTransport"]) == {
      Sid       = "DenyInsecureTransport"
      Effect    = "Deny"
      Principal = "*"
      Action    = "s3:*"
      Resource  = ["arn:aws:s3:::driftwatch-tfstate-123456789012-eu-west-1", "arn:aws:s3:::driftwatch-tfstate-123456789012-eu-west-1/*"]
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
    }
    error_message = "The bucket policy must deny every request not made over TLS."
  }

  assert {
    condition     = one([for s in jsondecode(aws_s3_bucket_policy.state.policy).Statement : s.Condition if s.Sid == "DenyOutdatedTls"]) == { NumericLessThan = { "s3:TlsVersion" = "1.2" } }
    error_message = "The bucket policy must deny TLS older than 1.2."
  }

  assert {
    condition     = alltrue([for s in jsondecode(aws_s3_bucket_policy.state.policy).Statement : s.Effect == "Deny"])
    error_message = "The bucket policy must only deny; access is granted through IAM."
  }
}

run "lifecycle_expires_old_versions" {
  command = apply

  assert {
    condition     = aws_s3_bucket_lifecycle_configuration.state.rule[0].noncurrent_version_expiration[0].noncurrent_days == 90
    error_message = "Noncurrent state versions must expire after the retention period."
  }
}

run "backend_config_uses_native_locking" {
  command = apply

  assert {
    condition     = strcontains(output.backend_config, "use_lockfile = true") && strcontains(output.backend_config, "encrypt      = true")
    error_message = "The generated backend config must enable S3-native locking and encryption."
  }
}

run "rejects_bad_prefix" {
  command = plan

  variables {
    bucket_prefix = "Driftwatch_State"
  }

  expect_failures = [var.bucket_prefix]
}

run "rejects_short_retention" {
  command = plan

  variables {
    noncurrent_version_retention_days = 1
  }

  expect_failures = [var.noncurrent_version_retention_days]
}
