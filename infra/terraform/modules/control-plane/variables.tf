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

variable "subnet_id" {
  description = "ID of the (public) subnet for the control-plane instance."
  type        = string

  validation {
    condition     = can(regex("^subnet-[0-9a-f]+$", var.subnet_id))
    error_message = "subnet_id must look like subnet-0123456789abcdef0."
  }
}

variable "instance_type" {
  description = "EC2 instance type. k3s plus the API (JVM), Keycloak and Postgres needs several GiB of memory."
  type        = string
  default     = "t3.large"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]*\\.[a-z0-9]+$", var.instance_type))
    error_message = "instance_type must look like t3.large."
  }
}

variable "architecture" {
  description = "CPU architecture of instance_type: amd64 (x86_64) or arm64 (Graviton)."
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
  description = "Size of the encrypted gp3 root volume in GiB (holds k3s images and the Postgres volume)."
  type        = number
  default     = 30

  validation {
    condition     = var.root_volume_size_gb >= 20 && var.root_volume_size_gb <= 200 && floor(var.root_volume_size_gb) == var.root_volume_size_gb
    error_message = "root_volume_size_gb must be a whole number from 20 to 200."
  }
}

variable "ebs_kms_key_arn" {
  description = "Optional customer-managed KMS key for the root volume. Null uses the account's default EBS key (aws/ebs)."
  type        = string
  default     = null

  validation {
    condition     = var.ebs_kms_key_arn == null || can(regex("^arn:aws[a-z-]*:kms:[a-z0-9-]+:[0-9]{12}:key/[0-9a-f-]{36}$", var.ebs_kms_key_arn))
    error_message = "ebs_kms_key_arn must be null or the full ARN of a KMS key."
  }
}

variable "k3s_version" {
  description = "Pinned k3s release, e.g. v1.36.5+k3s1. Change together with k3s_install_script_sha256."
  type        = string
  default     = "v1.36.5+k3s1"

  validation {
    condition     = can(regex("^v1\\.[0-9]+\\.[0-9]+\\+k3s[0-9]+$", var.k3s_version))
    error_message = "k3s_version must be an exact k3s release such as v1.36.5+k3s1 (no channels, no release candidates)."
  }
}

variable "k3s_install_script_sha256" {
  description = "sha256 of install.sh at the k3s_version tag. Cloud-init refuses to run a script that does not match."
  type        = string
  default     = "46177d4c99440b4c0311b67233823a8e8a2fc09693f6c89af1a7161e152fbfad"

  validation {
    condition     = can(regex("^[0-9a-f]{64}$", var.k3s_install_script_sha256))
    error_message = "k3s_install_script_sha256 must be 64 lowercase hex characters."
  }
}

variable "chart_ref" {
  description = "OCI reference of the Driftwatch control-plane Helm chart."
  type        = string
  default     = "oci://ghcr.io/tahausama210/charts/driftwatch"

  validation {
    condition     = can(regex("^oci://[a-z0-9.-]+(:[0-9]+)?/[a-z0-9._/-]+$", var.chart_ref))
    error_message = "chart_ref must be an oci:// reference without a tag (the version is chart_version)."
  }
}

variable "chart_version" {
  description = "Exact chart version to install (SemVer, no leading v), e.g. 0.2.0."
  type        = string

  validation {
    condition     = can(regex("^[0-9]+\\.[0-9]+\\.[0-9]+(-[0-9A-Za-z.-]+)?$", var.chart_version))
    error_message = "chart_version must be an exact SemVer version such as 0.2.0 (no ranges, no leading v)."
  }
}

variable "chart_values" {
  description = "Helm values for the chart, rendered into the HelmChart's valuesContent. Must not contain secrets: the enrollment token is provided through the Secret named by enrollment_secret_name."
  type        = any
  default     = {}

  validation {
    condition     = can(keys(var.chart_values))
    error_message = "chart_values must be an object (a map of Helm values)."
  }
}

variable "namespace" {
  description = "Kubernetes namespace for the control plane. It is created with Pod Security 'restricted' enforced."
  type        = string
  default     = "driftwatch"

  validation {
    condition     = can(regex("^[a-z0-9]([-a-z0-9]{0,61}[a-z0-9])?$", var.namespace))
    error_message = "namespace must be a valid Kubernetes namespace name."
  }
}

variable "enrollment_secret_name" {
  description = "Name of the Kubernetes Secret that cloud-init creates from the SSM parameter. The chart must reference it as an existing Secret."
  type        = string
  default     = "driftwatch-enrollment"

  validation {
    condition     = can(regex("^[a-z0-9]([-a-z0-9.]{0,251}[a-z0-9])?$", var.enrollment_secret_name))
    error_message = "enrollment_secret_name must be a valid Kubernetes object name."
  }
}

variable "enrollment_secret_key" {
  description = "Key inside the enrollment Secret that holds the token."
  type        = string
  default     = "enrollment-token"

  validation {
    condition     = can(regex("^[-._a-zA-Z0-9]{1,253}$", var.enrollment_secret_key))
    error_message = "enrollment_secret_key must be a valid Secret data key."
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

variable "client_security_group_ids" {
  description = "Security groups allowed to reach the HTTP ingress port, keyed by a static label (e.g. { fleet = sg-... }). Static keys keep for_each plannable before the groups exist."
  type        = map(string)
  default     = {}

  validation {
    condition     = alltrue([for id in values(var.client_security_group_ids) : can(regex("^sg-[0-9a-f]+$", id))])
    error_message = "Every client_security_group_ids value must be a security group ID (sg-...)."
  }
}

variable "admin_cidrs" {
  description = "IPv4 CIDRs allowed to reach the dashboard/API on the HTTP ingress port, e.g. [\"203.0.113.7/32\"]. Empty by default: nobody outside the VPC can connect."
  type        = list(string)
  default     = []

  validation {
    condition     = alltrue([for c in var.admin_cidrs : can(cidrnetmask(c))])
    error_message = "Every admin_cidrs entry must be a valid IPv4 CIDR."
  }

  validation {
    condition     = alltrue([for c in var.admin_cidrs : can(cidrnetmask(c)) && try(tonumber(split("/", c)[1]) >= 8, false)])
    error_message = "admin_cidrs must not contain 0.0.0.0/0 or any range wider than /8; list the addresses you administer from."
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
