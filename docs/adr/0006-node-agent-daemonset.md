# 6. The node agent as a narrowly mounted DaemonSet

- Status: accepted
- Date: 2026-11-09

## Context

On Kubernetes, the agent must scan each node, not the container it runs in. It needs
the node's `/etc` (including `/etc/shadow`, mode 0000 on RHEL-family systems) and its
`/proc/sys`. A node agent is a privileged component, so whatever it can read, an
attacker who compromises it can read too.

## Decision

- A separate chart, `driftwatch-agent`, runs one pod per node (tolerating every taint)
  in its own namespace. That namespace has Pod Security `privileged`, because hostPath
  requires it; the control-plane namespace stays `restricted`.
- It mounts only what the checks read, read-only and recursively read-only: the node's
  `/etc`, `/usr/lib` (the `os-release` symlink target) and `/proc`. It doesn't mount `/`,
  so kubelet's pod volumes, and with them other pods' Secrets, stay out of view.
  `mountUsrLib: false` drops the `/usr/lib` mount for minimal node images that have none
  (k3d's k3s nodes, whose `/etc/os-release` is a regular file); a missing hostPath
  directory would otherwise keep the pod from starting.
- The agent runs as root with every capability dropped except `DAC_READ_SEARCH`, no
  privilege escalation and no service account token. Its identity is kept in a hostPath
  state directory, so it survives pod restarts.
- `hostNetwork` defaults to `false`, so NetworkPolicies apply to the agent's traffic.

## Consequences

- `/etc` still holds node credentials (for example `k3s.yaml` on a k3s server), so the
  narrowing reduces exposure but doesn't remove it.
- Network sysctls are per network namespace. With `hostNetwork: false`, the four
  `network.*` checks report the pod's namespace, not the node's. Setting
  `hostNetwork: true` reports the node's values, but then NetworkPolicies no longer
  select the agent, and the control plane must admit node addresses
  (`networkPolicy.api.extraIngress`).
- On VMs (the Terraform fleet) the agent runs as a hardened systemd unit instead, as a
  transient non-root user with only `CAP_DAC_READ_SEARCH`.
