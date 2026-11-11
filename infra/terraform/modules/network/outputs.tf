output "vpc_id" {
  description = "ID of the VPC."
  value       = aws_vpc.this.id
}

output "vpc_cidr_block" {
  description = "IPv4 CIDR of the VPC."
  value       = aws_vpc.this.cidr_block
}

output "public_subnet_ids" {
  description = "IDs of the public subnets, one per availability zone, in AZ order."
  value       = aws_subnet.public[*].id
}

output "availability_zones" {
  description = "Availability zones the subnets are in."
  value       = local.azs
}
