variable "name" {
  description = "Name prefix for every resource in this module, e.g. driftwatch-demo."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,30}[a-z0-9]$", var.name))
    error_message = "name must be 3-32 characters of lowercase letters, digits and hyphens, starting with a letter."
  }
}

variable "vpc_id" {
  description = "ID of the VPC to launch into."
  type        = string

  validation {
    condition     = can(regex("^vpc-[0-9a-f]+$", var.vpc_id))
    error_message = "vpc_id must look like vpc-0123456789abcdef0."
  }
}

variable "vpc_cidr_block" {
  description = "IPv4 CIDR of the VPC. Agents may reach the control plane over HTTP only inside it."
  type        = string

  validation {
    condition     = can(cidrnetmask(var.vpc_cidr_block))
    error_message = "vpc_cidr_block must be a valid IPv4 CIDR."
  }
}

variable "subnet_ids" {
  description = "Subnets to spread instances across (round-robin, one per AZ is typical)."
  type        = list(string)

  validation {
    condition     = length(var.subnet_ids) >= 1 && alltrue([for s in var.subnet_ids : can(regex("^subnet-[0-9a-f]+$", s))])
    error_message = "subnet_ids must contain at least one subnet ID (subnet-...)."
  }
}

variable "instance_count" {
  description = "Number of fleet instances. Distributions are assigned round-robin from distros."
  type        = number
  default     = 3

  validation {
    condition     = var.instance_count >= 1 && var.instance_count <= 30 && floor(var.instance_count) == var.instance_count
    error_message = "instance_count must be a whole number from 1 to 30."
  }
}

variable "distros" {
  description = "Distributions to spread the fleet across, in assignment order. Supported: ubuntu-24.04, debian-12, al2023."
  type        = list(string)
  default     = ["ubuntu-24.04", "debian-12", "al2023"]

  validation {
    condition     = length(var.distros) >= 1 && length(distinct(var.distros)) == length(var.distros) && alltrue([for d in var.distros : contains(["ubuntu-24.04", "debian-12", "al2023"], d)])
    error_message = "distros must be a non-empty list of distinct values from ubuntu-24.04, debian-12 and al2023."
  }
}

variable "instance_type" {
  description = "EC2 instance type for every fleet host. The agent is a small static binary."
  type        = string
  default     = "t3.micro"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]*\\.[a-z0-9]+$", var.instance_type))
    error_message = "instance_type must look like t3.micro."
  }
}

variable "architecture" {
  description = "CPU architecture of instance_type: amd64 (x86_64) or arm64 (Graviton). Selects the AMIs and the agent archive."
  type        = string
  default     = "amd64"

  validation {
    condition     = contains(["amd64", "arm64"], var.architecture)
    error_message = "architecture must be amd64 or arm64."
  }

  validation {
    # Graviton families have a "g" right after the generation digit: t4g, m7g, c7gn, r8g.
    condition     = (var.architecture == "arm64") == can(regex("^[a-z]+[0-9]+g[a-z0-9-]*\\.", var.instance_type))
    error_message = "architecture must match instance_type: arm64 for Graviton types (t4g, m7g, ...), amd64 otherwise."
  }
}

variable "root_volume_size_gb" {
  description = "Size of each encrypted gp3 root volume in GiB (must be at least the AMI's snapshot size)."
  type        = number
  default     = 10

  validation {
    condition     = var.root_volume_size_gb >= 8 && var.root_volume_size_gb <= 100 && floor(var.root_volume_size_gb) == var.root_volume_size_gb
    error_message = "root_volume_size_gb must be a whole number from 8 to 100."
  }
}

variable "ebs_kms_key_arn" {
  description = "Optional customer-managed KMS key for root volumes. Null uses the account's default EBS key (aws/ebs)."
  type        = string
  default     = null

  validation {
    condition     = var.ebs_kms_key_arn == null || can(regex("^arn:aws[a-z-]*:kms:[a-z0-9-]+:[0-9]{12}:key/[0-9a-f-]{36}$", var.ebs_kms_key_arn))
    error_message = "ebs_kms_key_arn must be null or the full ARN of a KMS key."
  }
}

variable "server_url" {
  description = "Driftwatch API base URL the agents report to, e.g. http://10.42.0.10 (the control plane's private address)."
  type        = string

  validation {
    condition     = can(regex("^https?://[A-Za-z0-9.-]+(:[0-9]+)?(/.*)?$", var.server_url))
    error_message = "server_url must be an http(s) URL."
  }
}

variable "agent_version" {
  description = "Agent release to install (SemVer without the leading v, e.g. 0.2.0). Downloaded from the v<version> GitHub release."
  type        = string

  validation {
    condition     = can(regex("^[0-9]+\\.[0-9]+\\.[0-9]+(-[0-9A-Za-z.-]+)?$", var.agent_version))
    error_message = "agent_version must be an exact SemVer version such as 0.2.0 (no leading v, no ranges)."
  }
}

variable "agent_release_base_url" {
  description = "Base URL of the GitHub release downloads. Files are fetched from <base>/v<agent_version>/."
  type        = string
  default     = "https://github.com/tahausama210/driftwatch/releases/download"

  validation {
    condition     = can(regex("^https://[A-Za-z0-9.-]+(/[A-Za-z0-9._~-]+)*$", var.agent_release_base_url))
    error_message = "agent_release_base_url must be an https:// URL without a trailing slash."
  }
}

variable "checksums_sha256" {
  description = "Optional sha256 of the release's checksums.txt. When set, cloud-init also refuses a checksums.txt that does not match, pinning the release contents in Terraform."
  type        = string
  default     = null

  validation {
    condition     = var.checksums_sha256 == null || can(regex("^[0-9a-f]{64}$", var.checksums_sha256))
    error_message = "checksums_sha256 must be null or 64 lowercase hex characters."
  }
}

variable "agent_interval" {
  description = "Scan interval (Go duration, at least 10s), passed as DRIFTWATCH_INTERVAL."
  type        = string
  default     = "5m"

  validation {
    condition     = can(regex("^([0-9]+h)?([0-9]+m)?([0-9]+s)?$", var.agent_interval)) && var.agent_interval != "" && !can(regex("^[0-9]s$", var.agent_interval))
    error_message = "agent_interval must be a Go duration of whole h/m/s units, at least 10s (e.g. 30s, 5m, 1h)."
  }
}

variable "enrollment_token_parameter_arn" {
  description = "ARN of the SSM SecureString holding the enrollment token."
  type        = string

  validation {
    condition     = can(regex("^arn:aws[a-z-]*:ssm:[a-z0-9-]+:[0-9]{12}:parameter/[A-Za-z0-9_./-]+$", var.enrollment_token_parameter_arn))
    error_message = "enrollment_token_parameter_arn must be the full ARN of one SSM parameter, with no wildcards."
  }
}

variable "enrollment_token_kms_key_arn" {
  description = "ARN of the KMS key that encrypts the enrollment-token parameter."
  type        = string

  validation {
    condition     = can(regex("^arn:aws[a-z-]*:kms:[a-z0-9-]+:[0-9]{12}:key/[0-9a-f-]{36}$", var.enrollment_token_kms_key_arn))
    error_message = "enrollment_token_kms_key_arn must be the full ARN of one KMS key (not an alias)."
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
