# Driftwatch on AWS (Terraform)

A disposable AWS test environment for Driftwatch: a single-node k3s control plane running
the Helm chart, plus a fleet of real Ubuntu 24.04, Debian 12 and Amazon Linux 2023 hosts
whose agents enroll and report to it. Nothing can SSH in, and nothing on the internet can
reach it unless you add your own IP to `admin_cidrs`.

> This configuration is verified offline only (`fmt`, `validate`, `terraform test` with a
> mocked AWS provider, tflint, trivy). It has not been applied to a real account. Treat a
> first `apply` as a test, and read [Tradeoffs](#tradeoffs) first.

- [Architecture](#architecture)
- [Layout](#layout)
- [Security posture](#security-posture)
- [Usage: bootstrap, apply, destroy](#usage)
- [Modules](#modules) (inputs and outputs)
- [Tests](#tests)
- [Tradeoffs](#tradeoffs)
- [Cost](#cost)

## Architecture

```
                 internet
                    │  HTTP 80, only from var.admin_cidrs (empty by default)
                    ▼
 ┌──────────────── VPC 10.42.0.0/16 ─────────────────────────────────────────┐
 │  public subnet (AZ a)                     public subnets (AZ a, b)         │
 │  ┌──────────────────────────────┐         ┌─────────────────────────────┐  │
 │  │ control plane (EIP)          │ ◄─────  │ fleet: ubuntu / debian /    │  │
 │  │ Ubuntu 24.04, k3s v1.36.5    │ HTTP 80 │ al2023 × N                  │  │
 │  │ Traefik :80 → web, api,      │ from    │ driftwatch-agent (systemd)  │  │
 │  │ Keycloak (/auth), Postgres   │ fleet SG│ no inbound rules at all     │  │
 │  └──────────────────────────────┘         └─────────────────────────────┘  │
 │           │ HTTPS 443 out only                     │ HTTPS 443 out,        │
 │           ▼                                        ▼ HTTP 80 to VPC only   │
 └──── internet gateway (no NAT) ────────────────────────────────────────────┘
        GitHub releases · ghcr.io · SSM / KMS APIs

 operators ──► SSM Session Manager ──► any instance        (no SSH, no key pairs)
 SSM SecureString /driftwatch-demo/enrollment-token (KMS CMK) ──► read at boot by both roles
```

Boot sequence:

- **Control plane** (cloud-init): download `install.sh` from the pinned k3s tag and
  check it against a pinned sha256. `install.sh` then downloads the k3s binary and checks
  it against the release's `sha256sum` file. Cloud-init waits for the node, creates the
  `driftwatch` namespace (Pod Security `restricted`), reads the enrollment token from SSM
  into a Secret, and only then drops a `HelmChart` manifest. The k3s Helm controller
  installs `oci://ghcr.io/tahausama210/charts/driftwatch` at `chart_version`, with
  `publicUrl = http://<EIP>`, so the issuer is `http://<EIP>/auth/realms/driftwatch`.
- **Fleet** (cloud-init): install the SSM agent on Debian, whose AMI lacks it. Download
  `checksums.txt` and `driftwatch-agent_<version>_linux_<arch>.tar.gz` from the
  `v<version>` GitHub release, and refuse to install unless the archive's sha256 matches
  its single entry (optionally also pin the sha256 of `checksums.txt` itself). Then
  install and start the hardened `driftwatch-agent.service`. On every start the unit
  reads the token from SSM into `/run`, which is tmpfs.

## Layout

| Path | What |
|---|---|
| `modules/network` | VPC, one public subnet per AZ, IGW, routes, locked-down default SG, VPC Flow Logs |
| `modules/ssm-instance-role` | least-privilege EC2 role: Session Manager plus one SSM parameter, and the stdlib Python client that reads it |
| `modules/control-plane` | k3s server, security group, EIP, cloud-init that installs the chart |
| `modules/fleet` | N agent hosts across three distributions, security group, cloud-init and systemd unit |
| `envs/demo` | root module that wires everything together and generates the enrollment token |
| `bootstrap` | S3 state bucket (versioned, SSE-KMS, public access blocked, TLS-only) |
| `.tflint.hcl` | tflint config: `terraform` preset `all`, AWS ruleset 0.50.0 |

Requirements: Terraform `>= 1.10` for the modules and `>= 1.11` for `envs/demo`
(write-only arguments). AWS provider `~> 6.70`. Every directory commits a
`.terraform.lock.hcl` with hashes for linux_amd64, darwin_arm64 and windows_amd64.

## Security posture

| Requirement | How | Asserted by |
|---|---|---|
| No SSH | No security group has any port 22 rule. No `aws_key_pair` and no `key_name` anywhere. Access is through SSM Session Manager. | `control_plane`: `ingress_only_from_fleet_sg_and_admin_cidrs`, `instance_hardening`. `fleet`: `no_inbound_access` (also a source scan for ingress-rule and key-pair resources) |
| IMDSv2 required | `http_tokens = "required"` and hop limit 1, so k3s pods can't reach instance credentials | `instance_hardening` in both instance modules |
| EBS encrypted | gp3 root volumes, `encrypted = true`, optional CMK | `instance_hardening` in both |
| Least-privilege instance profile | No `AmazonSSMManagedInstanceCore`, which grants `ssm:GetParameter*` on every parameter. Instead: `ssm:UpdateInstanceInformation` on this account's instances, the four `ssmmessages:*Channel` actions ([AWS's custom Session Manager profile](https://docs.aws.amazon.com/systems-manager/latest/userguide/getting-started-create-iam-instance-profile.html)), `ssm:GetParameter` on the one token ARN, and `kms:Decrypt` on one key, only via `ssm.<region>.amazonaws.com`, with encryption context `PARAMETER_ARN` = that parameter. The trust policy is scoped by `aws:SourceAccount`. | `ssm_instance_role` (4 runs), `iam_reads_only_the_token_parameter` (control plane, fleet), `both_roles_read_only_the_one_parameter` (demo) |
| No `0.0.0.0/0` ingress | The fleet has no ingress rules. The control plane has only TCP 80 from the fleet SG and `admin_cidrs`, which validation limits to `/8` or narrower (so never `/0`). The default SG is emptied. | `rejects_open_admin_cidr` / `rejects_wide_admin_cidr` (control plane, demo), `locks_down_default_security_group` (network) |
| Control plane accepts the API port only from the fleet SG and `admin_cidrs` (empty default) | `aws_vpc_security_group_ingress_rule` per source | `no_ingress_by_default`, `ingress_only_from_fleet_sg_and_admin_cidrs`, demo `control_plane_admits_only_the_fleet_by_default` |
| Supply chain on boot | k3s `install.sh` pinned by sha256. Agent archive verified against `checksums.txt`, fail-closed, HTTPS only (`--proto =https`). | `user_data_pins_and_verifies_k3s`, `installer_verifies_checksums_and_fails_closed`, `pinned_checksums_file` |
| Token never in state or on disk | `ephemeral "random_password"` → `aws_ssm_parameter.value_wo` (SecureString, CMK with rotation). Instances read it at boot into a k8s Secret (k3s `secrets-encryption`) or `/run` (tmpfs). | demo `token_is_a_kms_encrypted_write_only_secure_string`, fleet `token_is_read_from_ssm_at_start_and_kept_off_disk` |
| State bucket | versioning, SSE-KMS (CMK, rotation, bucket key), all four public access blocks, `BucketOwnerEnforced`, policy denying non-TLS and TLS < 1.2, `force_destroy = false` | `bootstrap` (8 runs) |

Egress is TCP 443 to the internet (plus TCP 80 to the VPC CIDR for agents). Without a NAT
gateway or VPC endpoints, GitHub, ghcr.io and the SSM APIs are only reachable over the
internet. Time sync uses the link-local Amazon Time Sync Service (169.254.169.123), which
security groups don't filter.

### systemd hardening for the agent

The agent must read `/etc/shadow`, which is mode `0640 root:shadow` on Debian/Ubuntu and
`0000` on Amazon Linux, and it must see the real owner and mode of system files. The unit
doesn't run it as root. It runs it as a transient unprivileged user with exactly one
capability:

| Directive | Compatible? | Why |
|---|---|---|
| `DynamicUser=yes` + `AmbientCapabilities=CAP_DAC_READ_SEARCH` + `CapabilityBoundingSet=CAP_DAC_READ_SEARCH` | yes (used) | The capability bypasses read and search permission checks only, so the agent can read `/etc/shadow` but can't write anything it doesn't own. `stat()` needs no capability. |
| `ProtectSystem=strict`, `ProtectHome=yes`, `PrivateTmp`, `PrivateDevices`, `PrivateIPC` | yes (used) | The filesystem is read-only except `StateDirectory=driftwatch` (`/var/lib/driftwatch`, 0700). None of the checks read `/home` or `/root`. |
| `ProtectKernelTunables=yes` | yes (used) | Makes `/proc/sys` read-only. Reading kernel parameters still works. |
| `ProtectProc=invisible`, `ProtectKernelModules/Logs`, `ProtectControlGroups`, `ProtectClock`, `ProtectHostname` | yes (used) | The agent reads `/proc/sys`, not other processes. The hostname value is unchanged. |
| `NoNewPrivileges`, `RestrictSUIDSGID`, `RestrictNamespaces`, `RestrictRealtime`, `LockPersonality`, `MemoryDenyWriteExecute`, `SystemCallArchitectures=native`, `SystemCallFilter=@system-service ~@privileged` (EPERM), `RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6` | yes (used) | A static Go binary needs none of what these block. |
| `PrivateUsers=yes` | **no** | Inside a user namespace, host-owned files appear as `nobody` and the capability doesn't apply to them. Every owner-uid-0 and shadow check would fail. |
| `ProcSubset=pid` | **no** | Hides `/proc/sys`, so the `network.*` and `kernel.*` checks would report ERROR. |
| `PrivateNetwork=yes` | **no** | The agent would read a private namespace's `net.ipv4.*` values instead of the host's, and couldn't reach the API. |
| an empty `CapabilityBoundingSet=`, or one without `CAP_DAC_READ_SEARCH` | **no** | `/etc/shadow` would become unreadable and the `accounts.*` checks would report ERROR. |
| `InaccessiblePaths=` / `TemporaryFileSystem=` over `/etc` | **no** | Would hide the files being audited. |

The token fetch runs as `ExecStartPre=+…`: as root, outside the sandbox, writing
`/run/driftwatch-agent/agent.env` (0600), which systemd loads before `ExecStart`.

### trivy ignores (each inline, with its reason)

| ID | Where | Why |
|---|---|---|
| AVD-AWS-0164 | public subnets | Public by design: there is no NAT, and the instances have no inbound rules. |
| AVD-AWS-0104 | HTTPS egress (control plane, fleet) | Required without NAT or VPC endpoints. TCP 443 only. |
| AVD-AWS-0057 | Session Manager policy | `ssmmessages:*Channel` actions support no resource-level permissions. The actions are listed explicitly. |
| AVD-AWS-0089 | state bucket access logging | Would need a second bucket for a single-user state bucket. CloudTrail data events are the alternative. |
| AVD-AWS-0017 | flow-log group CMK | CloudWatch Logs already encrypts at rest. A CMK adds cost and a key policy for a 14-day demo log. |

## Usage

You need an AWS account, credentials for it in your shell (e.g. `aws sso login`), the AWS
CLI with the [Session Manager plugin](https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-working-with-install-plugin.html),
and Terraform ≥ 1.11. A Driftwatch release must exist: agent archives plus
`checksums.txt` on the GitHub Release, and the chart in GHCR.

### 1. Bootstrap the state bucket (once per account)

```sh
cd infra/terraform/bootstrap
terraform init
terraform apply                      # local state: keep bootstrap/terraform.tfstate safe
terraform output -raw backend_config > ../envs/demo/backend.hcl
```

### 2. Apply the demo

```sh
cd ../envs/demo
cp terraform.tfvars.example terraform.tfvars   # set agent_version, chart_version, admin_cidrs
terraform init -backend-config=backend.hcl     # S3 backend with use_lockfile = true
terraform plan -out=demo.tfplan
terraform apply demo.tfplan
terraform output
```

Without a backend (throwaway runs), use `terraform init -backend=false`. That keeps state
locally, so don't lose it before you destroy.

The control plane needs a few minutes after `apply` to install k3s and the chart. Then:

- **Dashboard**: open `dashboard_url` from an address in `admin_cidrs`. OIDC redirects go
  to that same public address, so login only works from there.
- **Shell on any host**: `terraform output ssm_session_commands`, then for example
  `sudo k3s kubectl get pods -n driftwatch` on the control plane, or
  `systemctl status driftwatch-agent` and `journalctl -u driftwatch-agent` on a fleet host.
- **Boot logs**: `/var/log/cloud-init-output.log`. A failed checksum shows up there as
  `FATAL`, and the agent is not installed.
- **API without opening admin_cidrs**: `terraform output -raw dashboard_port_forward_command`,
  then `curl localhost:8080/actuator/health`.
- **Rotate the enrollment token**: increment `enrollment_token_version` and apply. That
  writes a new value to SSM. Fleet hosts pick it up on their next service start. Agents
  that are already enrolled keep their agent tokens either way. The control plane's
  Secret was created at boot, so refresh it in a Session Manager shell:
  `sudo sh -c '/usr/local/libexec/driftwatch/get-enrollment-token | k3s kubectl -n driftwatch create secret generic driftwatch-enrollment --from-file=enrollment-token=/dev/stdin --dry-run=client -o yaml | k3s kubectl apply -f -'`,
  then restart the API pods.

### 3. Destroy

```sh
cd infra/terraform/envs/demo && terraform destroy
```

This removes everything, including the EIP and the KMS key (which is scheduled for
deletion after 7 days). The state bucket is kept on purpose. To remove it too, empty
every object version, then run `terraform destroy` in `bootstrap/`.

## Modules

### network

| Input | Type | Default | Description |
|---|---|---|---|
| `name` | string | required | Name prefix (3-32, lowercase) |
| `cidr_block` | string | `10.42.0.0/16` | RFC 1918, /16 to /24 |
| `az_count` | number | `2` | 2 to 4 AZs (fails if the region has fewer) |
| `subnet_newbits` | number | `4` | Subnet size relative to the VPC |
| `flow_logs_enabled` | bool | `true` | VPC Flow Logs to CloudWatch |
| `flow_logs_retention_days` | number | `14` | A CloudWatch retention value |
| `tags` | map(string) | `{}` | Extra tags (no `aws:` keys) |

Outputs: `vpc_id`, `vpc_cidr_block`, `public_subnet_ids`, `availability_zones`.

### ssm-instance-role

| Input | Type | Default | Description |
|---|---|---|---|
| `name` | string | required | Role and instance-profile name |
| `parameter_arn` | string | required | The one readable SSM parameter (no wildcards) |
| `kms_key_arn` | string | required | Its KMS key (an ARN, not an alias) |
| `tags` | map(string) | `{}` | Extra tags |

Outputs: `role_name`, `role_arn`, `instance_profile_name`, `parameter_name`,
`get_parameter_script` (stdlib Python, SigV4 over IMDSv2 credentials, so no AWS CLI is
needed on the AMIs), `policies`.

### control-plane

| Input | Type | Default | Description |
|---|---|---|---|
| `name`, `vpc_id`, `subnet_id` | string | required | Placement |
| `instance_type` / `architecture` | string | `t3.large` / `amd64` | Must agree (Graviton ⇒ `arm64`) |
| `root_volume_size_gb` | number | `30` | Encrypted gp3, 20 to 200 |
| `ebs_kms_key_arn` | string | `null` | Optional CMK for the root volume |
| `k3s_version` | string | `v1.36.5+k3s1` | Exact release only |
| `k3s_install_script_sha256` | string | pinned | sha256 of `install.sh` at that tag |
| `chart_ref` | string | `oci://ghcr.io/tahausama210/charts/driftwatch` | OCI chart |
| `chart_version` | string | required | Exact SemVer |
| `chart_values` | any | `{}` | Helm values (no secrets) |
| `namespace` | string | `driftwatch` | Created with PSA `restricted` |
| `enrollment_secret_name` / `_key` | string | `driftwatch-enrollment` / `enrollment-token` | Secret created from SSM |
| `enrollment_token_parameter_arn`, `enrollment_token_kms_key_arn` | string | required | The token |
| `client_security_group_ids` | map(string) | `{}` | SGs allowed on port 80 (static keys) |
| `admin_cidrs` | list(string) | `[]` | CIDRs allowed on port 80, never wider than /8 |
| `tags` | map(string) | `{}` | Extra tags |

Outputs: `instance_id`, `private_ip`, `public_ip`, `public_base_url`, `oidc_issuer`,
`http_port`, `security_group_id`, `ingress_sources`, `role_name`, `iam_policies`,
`user_data`.

### fleet

| Input | Type | Default | Description |
|---|---|---|---|
| `name`, `vpc_id`, `vpc_cidr_block` | string | required | Placement; HTTP egress is limited to the VPC CIDR |
| `subnet_ids` | list(string) | required | Round-robin placement |
| `instance_count` | number | `3` | 1 to 30 |
| `distros` | list(string) | `["ubuntu-24.04","debian-12","al2023"]` | Round-robin, distinct, from these three |
| `instance_type` / `architecture` | string | `t3.micro` / `amd64` | Must agree |
| `root_volume_size_gb` | number | `10` | Encrypted gp3 |
| `ebs_kms_key_arn` | string | `null` | Optional CMK |
| `server_url` | string | required | API base URL (the control plane's private IP) |
| `agent_version` | string | required | Exact SemVer, no `v` |
| `agent_release_base_url` | string | GitHub releases of this repo | HTTPS only |
| `checksums_sha256` | string | `null` | Optional pin for `checksums.txt` |
| `agent_interval` | string | `5m` | Go duration ≥ 10s |
| `enrollment_token_parameter_arn`, `enrollment_token_kms_key_arn` | string | required | The token |
| `tags` | map(string) | `{}` | Extra tags |

Outputs: `instance_ids`, `hosts`, `security_group_id`, `role_name`, `iam_policies`,
`user_data`, `install_script`, `systemd_unit`.

AMIs come from the vendors' public SSM parameters (no Marketplace subscriptions):
`/aws/service/canonical/ubuntu/server/24.04/stable/current/<arch>/hvm/ebs-gp3/ami-id`,
`/aws/service/debian/release/12/latest/<arch>`,
`/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-<x86_64|arm64>`.
`ignore_changes = [ami]` keeps a new AMI release from replacing running hosts.

### envs/demo

Inputs: `region` (`eu-west-1`), `name` (`driftwatch-demo`), `vpc_cidr_block`,
`admin_cidrs` (`[]`), `architecture`, `control_plane_instance_type`,
`fleet_instance_type`, `fleet_instance_count` (3), `agent_version` and `chart_version`
(required), `agent_checksums_sha256`, `enrollment_token_version` (1), `tags`.
Outputs: `dashboard_url`, `oidc_issuer`, `control_plane_instance_id`, `fleet_hosts`,
`ssm_session_commands`, `dashboard_port_forward_command`, `enrollment_token_parameter`.

## Tests

Everything runs offline, without credentials:

```sh
cd infra/terraform
terraform fmt -check -recursive
for d in modules/* envs/demo bootstrap; do
  (cd "$d" && terraform init -backend=false && terraform validate && terraform test)
done
docker run --rm -v "$PWD:/data" -w /data ghcr.io/terraform-linters/tflint:v0.65.0 --init --config /data/.tflint.hcl
docker run --rm -v "$PWD:/data" -w /data ghcr.io/terraform-linters/tflint:v0.65.0 --recursive --config /data/.tflint.hcl
docker run --rm -v "$PWD:/src:ro" aquasec/trivy:0.76.1 config --exit-code 1 /src
```

Every test uses `mock_provider "aws"`, so nothing touches AWS. `mock_data` and
`override_data` stand in for the AZ list, the AMI parameters, the caller identity, the
region and the partition. `mock_resource` defaults give IDs and ARNs a realistic shape,
so the modules' own input validation runs. Policies are built with `jsonencode()`
rather than `aws_iam_policy_document`, so tests can decode and compare them exactly. Each
module has negative `expect_failures` runs for its validations.

| Suite | Runs | Main assertions |
|---|---|---|
| `modules/network` | 13 | subnets in distinct AZs, IGW route and no NAT, empty default SG, tags, flow logs with a role scoped to their log group and assumable only by this account's flow logs, AZ precondition, CIDR/AZ/name/retention validation |
| `modules/ssm-instance-role` | 7 | EC2-only trust scoped by account, exactly five Session Manager actions with no wildcards, GetParameter on one ARN, Decrypt on one key via SSM with encryption context, wildcard ARN and alias rejected |
| `modules/control-plane` | 16 | no ingress by default, only fleet SG plus admin CIDRs on TCP 80, nothing covers 22, no 0.0.0.0/0, HTTPS-only egress, IMDSv2 with hop limit 1, encrypted gp3, no key pair, Ubuntu SSM AMI, IAM scope, k3s pinned and sha-verified, HelmChart from OCI at `chart_version`, PSA restricted, secrets encryption, issuer format, validation |
| `modules/fleet` | 22 | 3 hosts with one per distro, 7 hosts split 3/2/2 over distros and 4/3 over subnets, vendor AMI parameters (amd64 and arm64), no inbound rules, no key pairs, IMDSv2, encryption, IAM scope, installer verifies the sha256 before extracting and fails closed, optional checksums pin, token read from SSM into `/run`, every hardening directive present and the incompatible ones absent, SSM agent only on Debian, validation |
| `envs/demo` | 9 | SecureString with CMK, write-only token, wiring (issuer, chart values, agents' server URL), both roles scoped to the one parameter, closed by default, `admin_cidrs` pass-through, token rotation, validation |
| `bootstrap` | 8 | name, versioning, SSE-KMS with rotating key, public access block, ownership, TLS-only policy, lifecycle, `use_lockfile` backend config, validation |

What the mocks can't prove: AWS-side behaviour (AMI availability, Session Manager
connectivity, the Helm controller pulling OCI), and the scripts actually running on each
distribution. Those need a real `apply`.

## Tradeoffs

- **No NAT gateway.** Instances sit in public subnets with public IPv4 addresses, and
  their security groups allow no inbound traffic from the internet. That saves the NAT
  gateway's hourly and data charges, but every instance has a public address and
  internet egress on 443. For production, use private subnets with a NAT gateway, or VPC
  interface endpoints for `ssm`, `ssmmessages`, `kms` and `logs` plus an S3 gateway
  endpoint, and a registry mirror instead of direct GitHub and ghcr.io access.
- **HTTP inside the VPC.** Agents send their agent tokens to the control plane over plain
  HTTP on port 80, and the dashboard is HTTP too. The VPC limits who can observe the
  traffic, but it isn't encrypted. For production, use TLS on the ingress (cert-manager,
  or an ALB with ACM) and `https://` server URLs.
- **Dev-mode Keycloak.** The chart runs `start-dev` with embedded H2 and imports a realm
  whose demo users and client secrets are public in this repository. Anyone you let in
  through `admin_cidrs` can log in as `admin`. Keep `admin_cidrs` to your own /32 and
  destroy the stack after use. For production, use an external IdP or the Keycloak
  Operator.
- **Single node.** One EC2 instance holds k3s, Postgres (a local-path volume on the root
  EBS) and Keycloak. Losing the instance loses the data. That's fine for a test fleet.
- **Trust in the release.** `checksums.txt` comes from the same GitHub Release as the
  archive, so it detects corruption and tampering in transit, not a compromised release.
  Pin `agent_checksums_sha256` to fix the release contents in Terraform. Verifying a
  cosign signature over `checksums.txt` would be stronger, but the contract signs only
  images today. The Debian SSM agent package is trusted over HTTPS from AWS's regional
  bucket.
- **Fixed public address.** The OIDC issuer is the EIP, so login works only from
  `admin_cidrs`, not through an SSM port forward.

## Cost

This creates billable resources: EC2 instances and their EBS volumes, an Elastic IP and
the public IPv4 addresses (AWS charges for public IPv4), two KMS customer-managed keys
(the state key in `bootstrap` and the token key), CloudWatch Logs ingestion and storage
for flow logs, S3 storage and requests, and data transfer. Prices vary by region and
change over time, so check the [AWS Pricing Calculator](https://calculator.aws/) and the
pages for [EC2](https://aws.amazon.com/ec2/pricing/on-demand/),
[public IPv4 / VPC](https://aws.amazon.com/vpc/pricing/),
[KMS](https://aws.amazon.com/kms/pricing/) and
[CloudWatch](https://aws.amazon.com/cloudwatch/pricing/). Set
`flow_logs_enabled = false` in the network module if you don't need flow logs.

Free Tier terms depend on when your account was created. See
[aws.amazon.com/free](https://aws.amazon.com/free/). To list the instance types your
account can use under it, run
`aws ec2 describe-instance-types --filters Name=free-tier-eligible,Values=true --query 'InstanceTypes[].InstanceType'`.
The control plane's default `t3.large` is sized for k3s plus the JVM API, Keycloak and
Postgres, not for the Free Tier. Destroy the stack when you're done.
