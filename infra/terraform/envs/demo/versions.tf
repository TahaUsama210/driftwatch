terraform {
  # 1.11+ for write-only arguments (value_wo): the enrollment token never enters state.
  required_version = ">= 1.11"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.70"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.7"
    }
  }

  # Partial configuration: supply the rest with `terraform init -backend-config=backend.hcl`
  # (see backend.hcl.example and ../../bootstrap). Locking is S3-native (use_lockfile).
  backend "s3" {}
}
