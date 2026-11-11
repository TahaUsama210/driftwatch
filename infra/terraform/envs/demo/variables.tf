variable "region" {
  description = "AWS region to deploy the demo into."
  type        = string
  default     = "eu-west-1"

  validation {
    condition     = can(regex("^[a-z]{2}(-gov)?-[a-z]+-[0-9]$", var.region))
    error_message = "region must be an AWS region name such as eu-west-1."
  }
}

variable "name" {
  description = "Name prefix for every resource."
  type        = string
  default     = "driftwatch-demo"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,22}[a-z0-9]$", var.name))
    error_message = "name must be 3-24 characters of lowercase letters, digits and hyphens, starting with a letter."
  }
}

variable "vpc_cidr_block" {
  description = "IPv4 CIDR of the demo VPC."
  type        = string
  default     = "10.42.0.0/16"

  validation {
    condition     = can(cidrnetmask(var.vpc_cidr_block))
    error_message = "vpc_cidr_block must be a valid IPv4 CIDR."
  }
}

variable "admin_cidrs" {
  description = "IPv4 CIDRs allowed to open the dashboard (HTTP). Empty by default: the dashboard is closed to the internet."
  type        = list(string)
  default     = []

  validation {
    condition     = alltrue([for c in var.admin_cidrs : can(cidrnetmask(c)) && try(tonumber(split("/", c)[1]) >= 8, false)])
    error_message = "admin_cidrs must be valid IPv4 CIDRs no wider than /8 (never 0.0.0.0/0)."
  }
}

variable "architecture" {
  description = "CPU architecture for every instance: amd64 or arm64. Instance types must match."
  type        = string
  default     = "amd64"

  validation {
    condition     = contains(["amd64", "arm64"], var.architecture)
    error_message = "architecture must be amd64 or arm64."
  }
}

variable "control_plane_instance_type" {
  description = "Instance type of the k3s control plane."
  type        = string
  default     = "t3.large"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]*\\.[a-z0-9]+$", var.control_plane_instance_type))
    error_message = "control_plane_instance_type must look like t3.large."
  }
}

variable "fleet_instance_type" {
  description = "Instance type of each fleet host."
  type        = string
  default     = "t3.micro"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]*\\.[a-z0-9]+$", var.fleet_instance_type))
    error_message = "fleet_instance_type must look like t3.micro."
  }
}

variable "fleet_instance_count" {
  description = "Number of fleet hosts, spread round-robin across Ubuntu 24.04, Debian 12 and Amazon Linux 2023."
  type        = number
  default     = 3

  validation {
    condition     = var.fleet_instance_count >= 1 && var.fleet_instance_count <= 30 && floor(var.fleet_instance_count) == var.fleet_instance_count
    error_message = "fleet_instance_count must be a whole number from 1 to 30."
  }
}

variable "agent_version" {
  description = "Agent release to install on the fleet (SemVer without the leading v)."
  type        = string

  validation {
    condition     = can(regex("^[0-9]+\\.[0-9]+\\.[0-9]+(-[0-9A-Za-z.-]+)?$", var.agent_version))
    error_message = "agent_version must be an exact SemVer version such as 0.2.0."
  }
}

variable "agent_checksums_sha256" {
  description = "Optional sha256 of the agent release's checksums.txt, to pin the release contents in Terraform."
  type        = string
  default     = null

  validation {
    condition     = var.agent_checksums_sha256 == null || can(regex("^[0-9a-f]{64}$", var.agent_checksums_sha256))
    error_message = "agent_checksums_sha256 must be null or 64 lowercase hex characters."
  }
}

variable "chart_version" {
  description = "Version of the Driftwatch control-plane chart to install from GHCR."
  type        = string

  validation {
    condition     = can(regex("^[0-9]+\\.[0-9]+\\.[0-9]+(-[0-9A-Za-z.-]+)?$", var.chart_version))
    error_message = "chart_version must be an exact SemVer version such as 0.2.0."
  }
}

variable "enrollment_token_version" {
  description = "Bump to rotate the enrollment token: a new random value is written to SSM (write-only, never stored in state)."
  type        = number
  default     = 1

  validation {
    condition     = var.enrollment_token_version >= 1 && floor(var.enrollment_token_version) == var.enrollment_token_version
    error_message = "enrollment_token_version must be a positive whole number."
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
