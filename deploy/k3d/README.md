# Local Kubernetes (k3d)

Runs the whole platform on a three-node k3s cluster in Docker: the control plane
from `deploy/helm/driftwatch` and one agent per node from `deploy/helm/driftwatch-agent`.

```sh
deploy/k3d/up.sh      # create the cluster, build and import images, install both charts
deploy/k3d/smoke.sh   # end-to-end checks through the ingress
deploy/k3d/down.sh    # delete the cluster
```

Then open <http://localhost:38080/> and log in as `viewer` / `viewer-demo` or
`admin` / `admin-demo` (demo realm accounts, localhost only).

## Requirements

`docker`, `k3d` (5.x), `kubectl` and `helm` (4.x). Set `DOCKER`, `K3D`, `KUBECTL` or
`HELM` to a full path if a tool is not on `PATH`. `smoke.sh` also needs `curl` and
`jq`; when either is missing (Git Bash on Windows has no `jq`) it runs its HTTP checks
in a pinned `alpine` container instead, reaching the ingress through
`host.docker.internal` on Docker Desktop or the host network on Linux. Nothing else
needs to be installed.

All scripts are bash with `set -euo pipefail` and work from Git Bash on Windows and on
Linux CI runners. Paths passed to native Windows tools are converted with `pwd -W`.

## What `up.sh` does

| Step | Detail |
|---|---|
| Cluster | `k3d` cluster `driftwatch`, `rancher/k3s:v1.36.5-k3s1`, 1 server + 2 agents. Ingress (Traefik via the k3d load balancer) on `127.0.0.1:38080`, Kubernetes API on `127.0.0.1:36443`. Idempotent: an existing cluster is started and reused. |
| Kubeconfig | Written by `k3d kubeconfig write driftwatch` to its own file; your default kubeconfig and current context are not touched. `up.sh` prints the `export KUBECONFIG=...` line. |
| Images | Builds `driftwatch-{api,web,agent}:dev` from this repository (`SKIP_BUILD=1` reuses existing ones) and imports them with `k3d image import`. The local values set `pullPolicy: Never`, so a missing import fails loudly instead of pulling something else. The nodes pull the pinned Keycloak and Postgres images themselves (`docker save` of a multi-platform image from Docker Desktop's containerd store omits other platforms' layers, which breaks `k3d image import`). |
| Namespaces | `driftwatch` enforces Pod Security `restricted` (also `warn` and `audit`). `driftwatch-agent` is `privileged`, which hostPath mounts require; it holds only the agent. |
| Charts | Control plane with [`values-driftwatch.yaml`](values-driftwatch.yaml), then the agents with [`values-agent.yaml`](values-agent.yaml) (30 s scan interval). The control plane generates the enrollment token; `up.sh` copies it Secret-to-Secret into the agent namespace, so it never appears in values, in output or on a command line. |
| Wait | `helm --wait`, then `kubectl rollout status` for every workload. |

## What `smoke.sh` checks

All through `http://localhost:38080` (override with `BASE_URL`):

1. `/actuator/health/readiness` returns `UP` (Traefik → nginx → API, which needs Postgres).
2. Keycloak's discovery document under `/auth` names the issuer
   `http://localhost:38080/auth/realms/driftwatch`.
3. `/` serves the SPA, and `/config.json` names that issuer and the `driftwatch-web` client.
4. `GET /api/v1/hosts` without a token is `401` with `WWW-Authenticate: Bearer`.
5. The `driftwatch-smoke-viewer` client gets a token with the client-credentials grant
   from `/auth/realms/driftwatch/protocol/openid-connect/token`, and it can read the API.
6. Every node (`kubectl get nodes`) is an enrolled host with at least one scan.

Every check runs and is reported; the script exits non-zero if any failed. Tokens are
handed to curl in a mode-0600 header file and never printed.

## Troubleshooting

```sh
export KUBECONFIG="$(k3d kubeconfig write driftwatch)"
kubectl get pods -A -l app.kubernetes.io/part-of=driftwatch
kubectl -n driftwatch-agent logs -l app.kubernetes.io/name=driftwatch-agent --prefix
kubectl -n driftwatch get events --sort-by=.lastTimestamp
```

Port 38080 or 36443 already in use: both are reserved for this cluster in
`docs/CONTRACTS.md`; find the other listener with `Get-NetTCPConnection -LocalPort 38080`
(PowerShell) or `ss -ltnp` (Linux).
