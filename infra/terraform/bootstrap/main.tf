# Remote-state bucket for envs/*. Applied once per account with LOCAL state (it cannot
# store its own state in the bucket it creates); keep bootstrap's terraform.tfstate safe or
# import the resources again if it is lost.
#
# Hardening: versioning (recover from a bad write), SSE-KMS with a rotating customer key
# (state holds resource attributes), all public access blocked, ACLs disabled, TLS 1.2+
# required by bucket policy. Locking is S3-native (use_lockfile), so there is no DynamoDB
# table.

terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.70"
    }
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = merge(var.tags, {
      Project   = "driftwatch"
      ManagedBy = "terraform"
      Component = "terraform-state"
    })
  }
}

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}

locals {
  account_id  = data.aws_caller_identity.current.account_id
  partition   = data.aws_partition.current.partition
  bucket_name = "${var.bucket_prefix}-${local.account_id}-${var.region}"
  bucket_arn  = "arn:${local.partition}:s3:::${local.bucket_name}"
}

resource "aws_kms_key" "state" {
  description             = "Encrypts Driftwatch Terraform state"
  enable_key_rotation     = true
  deletion_window_in_days = 30

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "AccountAdministersKeyThroughIam"
      Effect    = "Allow"
      Principal = { AWS = "arn:${local.partition}:iam::${local.account_id}:root" }
      Action    = "kms:*"
      Resource  = "*"
    }]
  })
}

resource "aws_kms_alias" "state" {
  name          = "alias/${var.bucket_prefix}"
  target_key_id = aws_kms_key.state.key_id
}

#trivy:ignore:AVD-AWS-0089 Access logging needs a second bucket (and its own lifecycle) for a single-user demo state bucket; CloudTrail data events are the documented alternative.
resource "aws_s3_bucket" "state" {
  bucket = local.bucket_name

  # A versioned bucket with state in it must be emptied deliberately before deletion.
  force_destroy = false
}

resource "aws_s3_bucket_ownership_controls" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

resource "aws_s3_bucket_public_access_block" "state" {
  bucket = aws_s3_bucket.state.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "state" {
  bucket = aws_s3_bucket.state.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.state.arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "state" {
  bucket = aws_s3_bucket.state.id

  rule {
    id     = "expire-old-state-versions"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = var.noncurrent_version_retention_days
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  depends_on = [aws_s3_bucket_versioning.state]
}

resource "aws_s3_bucket_policy" "state" {
  bucket = aws_s3_bucket.state.id
  policy = jsonencode(local.bucket_policy)

  # The public access block must exist first, or the policy briefly applies without it.
  depends_on = [aws_s3_bucket_public_access_block.state]
}

locals {
  bucket_policy = {
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "DenyInsecureTransport"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource  = [local.bucket_arn, "${local.bucket_arn}/*"]
        Condition = { Bool = { "aws:SecureTransport" = "false" } }
      },
      {
        Sid       = "DenyOutdatedTls"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource  = [local.bucket_arn, "${local.bucket_arn}/*"]
        Condition = { NumericLessThan = { "s3:TlsVersion" = "1.2" } }
      },
    ]
  }
}
