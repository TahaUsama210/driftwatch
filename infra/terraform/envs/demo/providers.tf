provider "aws" {
  region = var.region

  default_tags {
    tags = merge(var.tags, {
      Project     = "driftwatch"
      Environment = "demo"
      ManagedBy   = "terraform"
      Repository  = "github.com/tahausama210/driftwatch"
    })
  }
}
