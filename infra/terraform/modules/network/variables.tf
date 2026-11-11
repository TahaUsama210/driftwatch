variable "name" {
  description = "Name prefix for every resource in this module, e.g. driftwatch-demo."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,30}[a-z0-9]$", var.name))
    error_message = "name must be 3-32 characters of lowercase letters, digits and hyphens, starting with a letter."
  }
}

variable "cidr_block" {
  description = "IPv4 CIDR of the VPC. A /16 to /24 private (RFC 1918) range."
  type        = string
  default     = "10.42.0.0/16"

  validation {
    condition     = try(cidrhost(var.cidr_block, 0) != "" && tonumber(split("/", var.cidr_block)[1]) >= 16 && tonumber(split("/", var.cidr_block)[1]) <= 24, false)
    error_message = "cidr_block must be a valid IPv4 CIDR with a prefix length between /16 and /24."
  }

  validation {
    # Mask the VPC's network address with each private range's prefix and compare.
    condition = try(anytrue([
      for range in ["10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"] :
      cidrhost("${cidrhost(var.cidr_block, 0)}/${split("/", range)[1]}", 0) == cidrhost(range, 0)
    ]), false)
    error_message = "cidr_block must be inside an RFC 1918 range (10.0.0.0/8, 172.16.0.0/12 or 192.168.0.0/16)."
  }
}

variable "az_count" {
  description = "Number of availability zones to spread public subnets across."
  type        = number
  default     = 2

  validation {
    condition     = var.az_count >= 2 && var.az_count <= 4 && floor(var.az_count) == var.az_count
    error_message = "az_count must be a whole number from 2 to 4."
  }
}

variable "subnet_newbits" {
  description = "Bits added to the VPC prefix for each subnet (4 turns a /16 into /20 subnets)."
  type        = number
  default     = 4

  validation {
    condition     = var.subnet_newbits >= 2 && var.subnet_newbits <= 8 && floor(var.subnet_newbits) == var.subnet_newbits
    error_message = "subnet_newbits must be a whole number from 2 to 8."
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

variable "flow_logs_enabled" {
  description = "Send VPC Flow Logs (all traffic) to a CloudWatch Logs group. CloudWatch ingestion and storage are billed."
  type        = bool
  default     = true

  validation {
    condition     = var.flow_logs_enabled != null
    error_message = "flow_logs_enabled must be true or false."
  }
}

variable "flow_logs_retention_days" {
  description = "Retention of the flow-log group, in days (a value CloudWatch Logs accepts)."
  type        = number
  default     = 14

  validation {
    condition     = contains([1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288, 3653], var.flow_logs_retention_days)
    error_message = "flow_logs_retention_days must be a CloudWatch Logs retention value such as 7, 14, 30 or 90."
  }
}
