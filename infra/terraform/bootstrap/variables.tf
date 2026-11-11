variable "region" {
  description = "Region of the state bucket (use the same region as envs/demo)."
  type        = string
  default     = "eu-west-1"

  validation {
    condition     = can(regex("^[a-z]{2}(-gov)?-[a-z]+-[0-9]$", var.region))
    error_message = "region must be an AWS region name such as eu-west-1."
  }
}

variable "bucket_prefix" {
  description = "Bucket name prefix. The account ID and region are appended for global uniqueness."
  type        = string
  default     = "driftwatch-tfstate"

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9-]{2,30}$", var.bucket_prefix))
    error_message = "bucket_prefix must be 3-31 characters of lowercase letters, digits and hyphens."
  }
}

variable "noncurrent_version_retention_days" {
  description = "Days to keep superseded state versions before they expire."
  type        = number
  default     = 90

  validation {
    condition     = var.noncurrent_version_retention_days >= 7 && var.noncurrent_version_retention_days <= 3650 && floor(var.noncurrent_version_retention_days) == var.noncurrent_version_retention_days
    error_message = "noncurrent_version_retention_days must be a whole number from 7 to 3650."
  }
}

variable "tags" {
  description = "Extra tags merged into the provider's default_tags."
  type        = map(string)
  default     = {}

  validation {
    condition     = alltrue([for k in keys(var.tags) : !startswith(lower(k), "aws:")])
    error_message = "Tag keys must not start with the reserved prefix aws:."
  }
}
