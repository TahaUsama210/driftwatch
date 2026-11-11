# Helm charts

| Chart | Installs | Namespace (Pod Security) |
|---|---|---|
| [`driftwatch`](driftwatch) | api (Deployment, 2 replicas, PDB), web (nginx Deployment, 2 replicas, PDB), Keycloak in dev mode, Postgres StatefulSet, Services, one Ingress, NetworkPolicies, optional ServiceMonitor | `driftwatch` (`restricted`) |
| [`driftwatch-agent`](driftwatch-agent) | agent DaemonSet on every node, its enrollment Secret and NetworkPolicy | `driftwatch-agent` (`privileged`) |

Both are version 0.2.0 and need Kubernetes 1.33 or newer. The local cluster in
[`../k3d`](../k3d) installs both; `up.sh` shows the full sequence.

```sh
kubectl create namespace driftwatch
kubectl label namespace driftwatch pod-security.kubernetes.io/enforce=restricted
helm upgrade --install driftwatch deploy/helm/driftwatch -n driftwatch \
  --set publicUrl=https://driftwatch.example.com --set ingress.host=driftwatch.example.com

kubectl create namespace driftwatch-agent
kubectl label namespace driftwatch-agent pod-security.kubernetes.io/enforce=privileged
# copy the control plane's enrollment token into driftwatch-agent/driftwatch-enrollment, then:
helm upgrade --install driftwatch-agent deploy/helm/driftwatch-agent -n driftwatch-agent \
  --set enrollment.existingSecret=driftwatch-enrollment
```

Namespaces are created outside the charts, so their Pod Security labels are in place
before the first pod is admitted. Each chart's NOTES warn when its namespace does not
enforce the expected level.

## Tests

```sh
bash scripts/check-helm.sh      # or: make check-helm; needs only docker
```

Runs, with digest-pinned images, `helm lint` and the helm-unittest suites in `*/tests/`
for both charts, then renders each chart once per `ci/*-values.yaml` (the
chart-testing convention) and validates the result with `kubeconform -strict` against
Kubernetes 1.36.6 schemas, plus the CRD catalog for the optional `ServiceMonitor`.
Finally it scans each chart with `trivy config`. The rendered configurations are:
control plane defaults; external Postgres, external IdP and TLS; agent defaults; and
the agent with host networking. The agent chart's files carry a render-only enrollment
token, since the chart refuses to render without one. Other toggles (NetworkPolicies
and Ingress off, the ServiceMonitor) are covered by the unit tests. `ci/` is excluded
from packaged charts.

The suites cover: the restricted security context of every container (including the
Keycloak init container); probes and resources; NetworkPolicy contents and which
toggles add or remove rules; Secrets that are explicit, taken from `existingSecret`,
generated, or kept from the cluster on upgrade (via a mocked `lookup`); Keycloak and
Postgres toggles and the validation errors for incomplete external settings;
ServiceMonitor gating; and the agent's mounts, capabilities, tolerations and token
sources.

## `driftwatch` values

Full defaults with comments are in [`driftwatch/values.yaml`](driftwatch/values.yaml);
[`values.schema.json`](driftwatch/values.schema.json) rejects unknown keys and bad types.

| Key | Default | Meaning |
|---|---|---|
| `publicUrl` | `http://localhost:38080` | Origin of the dashboard. Fixes Keycloak's `KC_HOSTNAME` (`<publicUrl>/auth`) and the issuer (`<publicUrl>/auth/realms/driftwatch`). |
| `enrollment.token` / `.existingSecret` / `.existingSecretKey` | generated / `""` / `enrollment-token` | Agent enrollment secret (24+ characters). |
| `oidc.issuer` | derived from `publicUrl` | Required when `keycloak.enabled=false`. |
| `oidc.jwksUri` | in-cluster Keycloak Service | Signing keys. With an external IdP, `<issuer>/protocol/openid-connect/certs`. |
| `oidc.audience`, `oidc.webClientId` | `driftwatch-api`, `driftwatch-web` | Required `aud` entry; the SPA's public client. |
| `api.replicaCount`, `api.staleAfter`, `api.extraEnv` | `2`, `PT5M`, `[]` | |
| `api.podDisruptionBudget` | `{enabled: true, maxUnavailable: 1}` | Same shape for `web`. |
| `web.dnsResolver` | `kube-dns.kube-system.svc.cluster.local` | nginx resolves upstreams per request and needs a resolver address. |
| `web.keycloakUpstream` | in-cluster Keycloak Service | Where nginx proxies `/auth/`. |
| `keycloak.enabled` | `true` | Dev-mode Keycloak with the realm from `files/keycloak/driftwatch-realm.json`. |
| `keycloak.admin.password` / `.existingSecret` | generated | Temporary master-realm admin. |
| `postgresql.enabled` | `true` | Single-instance Postgres 18 StatefulSet. |
| `postgresql.auth.{username,database,password,existingSecret}` | `driftwatch`, `driftwatch`, generated | |
| `postgresql.persistence.{storageClass,size}` | cluster default, `2Gi` | |
| `externalDatabase.{host,port,database,username,existingSecret,existingSecretKey,jdbcParameters}` | | Used when `postgresql.enabled=false`; `host` and `existingSecret` are then required. |
| `ingress.{enabled,className,host,annotations,tls}` | `true`, `traefik`, any host | One rule, `/` to the web Service. |
| `metrics.serviceMonitor.enabled` | `false` | Rendered only if the cluster serves `monitoring.coreos.com/v1`. |
| `networkPolicy.enabled` | `true` | See below. |
| `networkPolicy.{dns,ingressController,agents}` | k3s defaults | Selectors for CoreDNS, Traefik and the agent pods. |
| `networkPolicy.metricsFrom` | namespace `monitoring` | Scrapers admitted to the API when a ServiceMonitor is rendered. |
| `networkPolicy.api.{extraIngress,extraEgress}` | `[]` | e.g. egress to an external database or IdP. |
| `networkPolicy.web.extraEgress` | `[]` | Egress for `web.keycloakUpstream` when it is outside the release. |
| `*.image.{registry,repository,tag,digest,pullPolicy}` | `ghcr.io/tahausama210/driftwatch-*`, appVersion | A digest wins over the tag. Keycloak `26.8.1`, Postgres `18.6-alpine`. |
| `*.resources`, `*.nodeSelector`, `*.tolerations`, `*.affinity`, `*.podAnnotations` | | Per component. |

## `driftwatch-agent` values

| Key | Default | Meaning |
|---|---|---|
| `serverUrl` | `http://driftwatch-api.driftwatch.svc.cluster.local:8080` | The API Service of a `driftwatch` release in namespace `driftwatch`. |
| `enrollment.token` / `.existingSecret` / `.existingSecretKey` | none | Exactly one is required; never generated, since it must match the control plane. |
| `interval`, `logFormat` | `5m`, `json` | |
| `stateHostPath` | `/var/lib/driftwatch` | Node directory for `state.json`, so a node keeps its host identity. |
| `hostMountsRecursiveReadOnly` | `Enabled` | Makes submounts of the three host mounts read-only too. `IfPossible` for older runtimes. |
| `hostNetwork` | `false` | With `false`, the `network.*` checks read the pod's network namespace, not the node's. See "network checks" below. |
| `tolerations` | `[{operator: Exists}]` | Every node, including tainted ones. |
| `networkPolicy.{enabled,api,dns}` | on | Egress to the API pods and DNS only; no ingress. |

## Security posture

**Control plane (Pod Security `restricted`).** Every pod runs as a fixed non-root UID
(api 10001, web 101, Keycloak 1000, Postgres 70) with `seccompProfile: RuntimeDefault`.
Every container, the init container included, has `readOnlyRootFilesystem`, no
privilege escalation and all capabilities dropped. These settings live in one helper
and are not configurable. Writable paths are `emptyDir`s with size limits: `/tmp` for
Tomcat, `/etc/nginx/conf.d` (rendered templates) and `/tmp` (pid and temp paths) for
nginx, `/opt/keycloak/{data,lib/quarkus}` and `/tmp` for Keycloak, and the socket
directory and `/tmp` for Postgres. Keycloak's `start-dev` rewrites its Quarkus build at
startup, so an init container seeds `lib/quarkus` from the image into an `emptyDir`.
No pod mounts a service account token or gets service-link environment variables. Every
container has requests, limits and probes, and the API has a startup probe so its
liveness check can stay strict.

**Secrets.** Nothing secret is in a template. Each secret comes from a value, from an
`existingSecret`, or is generated with `randAlphaNum`. Generated values are read back
with `lookup` on upgrade, so they never rotate by accident. The Postgres password
Secret has `helm.sh/resource-policy: keep`, because the StatefulSet's volume (retained
on delete and scale-down) outlives the release and still needs it. An external
database password is accepted only as an `existingSecret`. `helm template` cannot
`lookup` and always generates new values, so GitOps tools that render without cluster
access should set `existingSecret`.

**NetworkPolicies** (enforced by k3s's embedded network policy controller). A default
deny covers every pod of the release; it is scoped to the release, not the whole
namespace. The allows on top of it:

| Pod | Ingress from | Egress to |
|---|---|---|
| web | Traefik pods in `kube-system` | api:8080, keycloak:8080, DNS |
| api | web, agent pods in `driftwatch-agent`, Prometheus (only with a ServiceMonitor) | postgresql:5432, keycloak:8080 (JWKS), DNS |
| keycloak | web, api | nothing |
| postgresql | api | nothing |

Kubelet probes come from the node and are allowed by the policy controller.
`/actuator/prometheus` is not exposed through the ingress (nginx proxies only
`/actuator/health`).

**Agent (Pod Security `privileged`).** The agent reads node files: `/etc/{hostname,
os-release,machine-id,passwd,shadow,login.defs,crontab}`, `/etc/ssh/**`,
`/usr/lib/os-release` and `/proc/sys`. That takes hostPath volumes, which `baseline`
and `restricted` both forbid, so its namespace must be `privileged`, and it should
hold nothing else. Within that, the pod is as narrow as the job allows:

- Only `/etc`, `/usr/lib` and `/proc` are mounted, at `/host/etc`, `/host/usr/lib` and
  `/host/proc` (`DRIFTWATCH_ROOT=/host`, `DRIFTWATCH_PROC=/host/proc`). Each is
  `readOnly` with `recursiveReadOnly: Enabled` and no mount propagation. The only
  writable path is the state directory.
- It runs as root, because the checks must stat and read files as root sees them. It
  drops every capability and adds back only `CAP_DAC_READ_SEARCH`. On RHEL, Fedora and
  Amazon Linux, `/etc/shadow` is mode `0000`, which even root cannot open without a
  DAC capability. `DAC_READ_SEARCH` bypasses read and directory-search checks only.
  `DAC_OVERRIDE` would also bypass write checks. On Debian and Ubuntu (`0640
  root:shadow`) the capability is not needed, but one image serves every distribution.
- `privileged: false`, `allowPrivilegeEscalation: false`, read-only root filesystem,
  `seccompProfile: RuntimeDefault`, no host PID, IPC or network namespace, no service
  account token, no RBAC.
- The NetworkPolicy allows egress to the API pods and DNS only, and no ingress.

Not mounting `/` keeps kubelet pod volumes (`/var/lib/kubelet`, where other pods'
Secrets and service account tokens live) and container filesystems out of view. It
does not hide every secret: `/etc` still holds node credentials, such as
`/etc/rancher/k3s/k3s.yaml` (a cluster-admin kubeconfig) on k3s servers, and
`/etc/shadow`, which the agent must read. Treat the agent image and its namespace with
node-level trust: pin the image by digest in production and restrict who can change
the DaemonSet.

**Network checks.** The `network.*` checks read `net.ipv4` sysctls, which are per
network namespace. With the default `hostNetwork: false` they report the pod's network
namespace, not the node's, so they do not describe the node's own settings. `hostNetwork: true` makes them describe the node.
NetworkPolicies cannot select host-network pods, so the agent chart then renders no
policy, and the control plane must admit node addresses through
`networkPolicy.api.extraIngress` (an `ipBlock` for the node CIDR).

## Production notes

- **Database.** Set `postgresql.enabled=false` and use a managed Postgres 18
  (`externalDatabase.*`, `existingSecret`, `jdbcParameters: sslmode=verify-full`).
  Add an egress rule for it in `networkPolicy.api.extraEgress`. The bundled
  StatefulSet has no replication or backups.
- **Identity.** Dev-mode Keycloak keeps state in H2 on an `emptyDir` and re-imports
  the demo realm, with its published secrets, on every start. Run Keycloak with the
  Keycloak Operator on a real database, or use your IdP, then set
  `keycloak.enabled=false`, `oidc.issuer`, optionally `oidc.jwksUri`, and an egress rule
  to the IdP. If the IdP should stay same-origin under `/auth`, set
  `web.keycloakUpstream` plus a matching `networkPolicy.web.extraEgress` rule.
- **TLS.** Set `publicUrl` to the `https://` origin, `ingress.host`, and `ingress.tls`
  (for example a cert-manager Certificate's Secret). TLS terminates at the ingress;
  pod-to-pod traffic is plain HTTP inside the cluster, so add a service mesh if that
  hop must be encrypted.
- **Images.** Pin `*.image.digest` to the signed release digests.
- **Monitoring.** With the Prometheus Operator installed, set
  `metrics.serviceMonitor.enabled=true`, add its selector labels in
  `metrics.serviceMonitor.labels`, and point `networkPolicy.metricsFrom` at its namespace.
