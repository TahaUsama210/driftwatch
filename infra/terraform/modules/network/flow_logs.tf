# VPC Flow Logs to CloudWatch Logs, for auditing what the instances talk to. The delivery
# role can write only to this one log group.

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}
data "aws_region" "current" {}

locals {
  flow_log_group_name = "/aws/vpc-flow-logs/${var.name}"
  account_id          = data.aws_caller_identity.current.account_id
}

#trivy:ignore:AVD-AWS-0017 CloudWatch Logs encrypts log data at rest by default; a customer-managed key adds a key policy and cost for a short-retention demo log.
resource "aws_cloudwatch_log_group" "flow_logs" {
  count = var.flow_logs_enabled ? 1 : 0

  name              = local.flow_log_group_name
  retention_in_days = var.flow_logs_retention_days

  tags = merge(var.tags, { Name = "${var.name}-flow-logs" })
}

resource "aws_iam_role" "flow_logs" {
  count = var.flow_logs_enabled ? 1 : 0

  name        = "${var.name}-vpc-flow-logs"
  description = "Lets VPC Flow Logs deliver to ${local.flow_log_group_name} only."
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "VpcFlowLogsAssumeRole"
      Effect    = "Allow"
      Principal = { Service = "vpc-flow-logs.amazonaws.com" }
      Action    = "sts:AssumeRole"
      # Confused-deputy guard: only flow logs in this account and region may use the role.
      Condition = {
        StringEquals = { "aws:SourceAccount" = local.account_id }
        ArnLike      = { "aws:SourceArn" = "arn:${data.aws_partition.current.partition}:ec2:${data.aws_region.current.region}:${local.account_id}:vpc-flow-log/*" }
      }
    }]
  })

  tags = merge(var.tags, { Name = "${var.name}-vpc-flow-logs" })
}

resource "aws_iam_role_policy" "flow_logs" {
  count = var.flow_logs_enabled ? 1 : 0

  name = "deliver-to-log-group"
  role = aws_iam_role.flow_logs[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid    = "DeliverFlowLogs"
      Effect = "Allow"
      Action = [
        "logs:CreateLogStream",
        "logs:PutLogEvents",
        "logs:DescribeLogStreams",
      ]
      Resource = ["${aws_cloudwatch_log_group.flow_logs[0].arn}:*"]
    }]
  })
}

resource "aws_flow_log" "this" {
  count = var.flow_logs_enabled ? 1 : 0

  vpc_id                   = aws_vpc.this.id
  traffic_type             = "ALL"
  log_destination_type     = "cloud-watch-logs"
  log_destination          = aws_cloudwatch_log_group.flow_logs[0].arn
  iam_role_arn             = aws_iam_role.flow_logs[0].arn
  max_aggregation_interval = 600

  tags = merge(var.tags, { Name = "${var.name}-flow-logs" })
}
