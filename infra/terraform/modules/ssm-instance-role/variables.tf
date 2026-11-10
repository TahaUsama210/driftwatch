variable "name" {
  description = "Name of the IAM role and instance profile."
  type        = string

  validation {
    condition     = can(regex("^[A-Za-z0-9+=,.@_-]{1,64}$", var.name))
    error_message = "name must be a valid IAM role name (1-64 characters of A-Z a-z 0-9 +=,.@_-)."
  }
}

variable "parameter_arn" {
  description = "ARN of the single SSM SecureString parameter the instance may read (the enrollment token)."
  type        = string

  validation {
    condition     = can(regex("^arn:aws[a-z-]*:ssm:[a-z0-9-]+:[0-9]{12}:parameter/[A-Za-z0-9_./-]+$", var.parameter_arn))
    error_message = "parameter_arn must be the full ARN of one SSM parameter, with no wildcards."
  }
}

variable "kms_key_arn" {
  description = "ARN of the customer-managed KMS key that encrypts the parameter."
  type        = string

  validation {
    condition     = can(regex("^arn:aws[a-z-]*:kms:[a-z0-9-]+:[0-9]{12}:key/[0-9a-f-]{36}$", var.kms_key_arn))
    error_message = "kms_key_arn must be the full ARN of one KMS key (not an alias), with no wildcards."
  }
}

variable "tags" {
  description = "Tags added to every resource, on top of the provider's default_tags."
  type        = map(string)
  default     = {}

  validation {
    condition     = alltrue([for k in keys(var.tags) : !startswith(lower(k), "aws:")])
    error_message = "Tag keys must not start with the reserved prefix aws:."
  }
}
