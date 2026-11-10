# Unit tests for the network module. No AWS access: the provider is mocked and the
# availability-zone lookup is overridden.

mock_provider "aws" {
  mock_resource "aws_cloudwatch_log_group" {
    defaults = { arn = "arn:aws:logs:eu-west-1:123456789012:log-group:/aws/vpc-flow-logs/test" }
  }
  mock_resource "aws_iam_role" {
    defaults = { arn = "arn:aws:iam::123456789012:role/test-vpc-flow-logs" }
  }
  mock_data "aws_caller_identity" {
    defaults = { account_id = "123456789012" }
  }
  mock_data "aws_partition" {
    defaults = { partition = "aws" }
  }
  mock_data "aws_region" {
    defaults = { region = "eu-west-1" }
  }
  override_data {
    target = data.aws_availability_zones.available
    values = {
      names = ["eu-west-1a", "eu-west-1b", "eu-west-1c"]
    }
  }
}

variables {
  name = "driftwatch-test"
  tags = { Project = "driftwatch" }
}

run "spreads_public_subnets_across_azs" {
  command = apply

  assert {
    condition     = length(aws_subnet.public) == 2
    error_message = "Expected one public subnet per AZ (az_count = 2)."
  }

  assert {
    condition     = length(distinct(aws_subnet.public[*].availability_zone)) == 2
    error_message = "Each public subnet must be in a different availability zone."
  }

  assert {
    condition     = jsonencode(output.availability_zones) == jsonencode(["eu-west-1a", "eu-west-1b"])
    error_message = "Subnets must use the first az_count available zones in order."
  }

  assert {
    condition     = aws_subnet.public[0].cidr_block == "10.42.0.0/20" && aws_subnet.public[1].cidr_block == "10.42.16.0/20"
    error_message = "Subnet CIDRs must be carved from the VPC CIDR with subnet_newbits."
  }

  assert {
    condition     = alltrue([for s in aws_subnet.public : s.map_public_ip_on_launch])
    error_message = "Public subnets must assign public IPv4 addresses (there is no NAT gateway)."
  }
}

run "routes_through_internet_gateway_without_nat" {
  command = apply

  assert {
    condition     = aws_route.internet.destination_cidr_block == "0.0.0.0/0" && aws_route.internet.gateway_id == aws_internet_gateway.this.id
    error_message = "The default route must go to the internet gateway."
  }

  assert {
    condition     = aws_route.internet.nat_gateway_id == null || aws_route.internet.nat_gateway_id == ""
    error_message = "There must be no NAT gateway route."
  }

  assert {
    condition     = length(aws_route_table_association.public) == 2
    error_message = "Every public subnet must be associated with the public route table."
  }

  assert {
    condition     = aws_vpc.this.enable_dns_support && aws_vpc.this.enable_dns_hostnames
    error_message = "VPC DNS support and hostnames are needed for SSM and private DNS names."
  }
}

run "locks_down_default_security_group" {
  command = apply

  assert {
    condition     = length(aws_default_security_group.this.ingress) == 0 && length(aws_default_security_group.this.egress) == 0
    error_message = "The default security group must have no rules."
  }
}

run "applies_tags" {
  command = apply

  assert {
    condition     = aws_vpc.this.tags["Project"] == "driftwatch" && aws_vpc.this.tags["Name"] == "driftwatch-test"
    error_message = "Caller tags and a Name tag must be applied."
  }

  assert {
    condition     = alltrue([for s in aws_subnet.public : s.tags["Tier"] == "public" && s.tags["Project"] == "driftwatch"])
    error_message = "Subnets must carry the caller tags and Tier = public."
  }
}

run "three_azs" {
  command = apply

  variables {
    az_count = 3
  }

  assert {
    condition     = length(aws_subnet.public) == 3 && length(distinct(aws_subnet.public[*].availability_zone)) == 3
    error_message = "az_count = 3 must create three subnets in three AZs."
  }
}

run "rejects_too_many_azs_for_region" {
  command = plan

  variables {
    az_count = 4
  }

  expect_failures = [aws_vpc.this]
}

run "rejects_public_cidr" {
  command = plan

  variables {
    cidr_block = "8.8.0.0/16"
  }

  expect_failures = [var.cidr_block]
}

run "rejects_oversized_cidr" {
  command = plan

  variables {
    cidr_block = "10.0.0.0/8"
  }

  expect_failures = [var.cidr_block]
}

run "rejects_single_az" {
  command = plan

  variables {
    az_count = 1
  }

  expect_failures = [var.az_count]
}

run "rejects_bad_name" {
  command = plan

  variables {
    name = "Driftwatch_Demo"
  }

  expect_failures = [var.name]
}

run "flow_logs_on_by_default_with_scoped_role" {
  command = apply

  assert {
    condition     = length(aws_flow_log.this) == 1 && aws_flow_log.this[0].traffic_type == "ALL" && aws_flow_log.this[0].vpc_id == aws_vpc.this.id
    error_message = "VPC Flow Logs must capture all traffic by default."
  }

  assert {
    condition     = aws_cloudwatch_log_group.flow_logs[0].retention_in_days == 14
    error_message = "The flow-log group must have a bounded retention."
  }

  assert {
    condition     = jsondecode(aws_iam_role_policy.flow_logs[0].policy).Statement[0].Resource == ["${aws_cloudwatch_log_group.flow_logs[0].arn}:*"]
    error_message = "The delivery role may write only to the flow-log group."
  }

  assert {
    condition = jsondecode(aws_iam_role.flow_logs[0].assume_role_policy).Statement[0].Condition == {
      StringEquals = { "aws:SourceAccount" = "123456789012" }
      ArnLike      = { "aws:SourceArn" = "arn:aws:ec2:eu-west-1:123456789012:vpc-flow-log/*" }
    }
    error_message = "Only VPC Flow Logs in this account and region may assume the delivery role (confused-deputy guard)."
  }
}

run "flow_logs_can_be_disabled" {
  command = apply

  variables {
    flow_logs_enabled = false
  }

  assert {
    condition     = length(aws_flow_log.this) == 0 && length(aws_iam_role.flow_logs) == 0
    error_message = "flow_logs_enabled = false must create no flow-log resources."
  }
}

run "rejects_invalid_flow_log_retention" {
  command = plan

  variables {
    flow_logs_retention_days = 10
  }

  expect_failures = [var.flow_logs_retention_days]
}
