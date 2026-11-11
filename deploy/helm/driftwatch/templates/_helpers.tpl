{{/* Names */}}

{{- define "driftwatch.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Release-scoped base name. Component names append a suffix (at most 15 characters,
"-keycloak-realm"), so the base is cut to 48 to keep every name within 63.
*/}}
{{- define "driftwatch.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 48 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 48 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 48 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "driftwatch.componentName" -}}
{{- printf "%s-%s" (include "driftwatch.fullname" .ctx) .component -}}
{{- end -}}

{{- define "driftwatch.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "driftwatch.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/* Labels. Usage: include "driftwatch.labels" (dict "ctx" $ "component" "api") */}}

{{- define "driftwatch.selectorLabels" -}}
app.kubernetes.io/name: {{ include "driftwatch.name" .ctx }}
app.kubernetes.io/instance: {{ .ctx.Release.Name }}
{{- with .component }}
app.kubernetes.io/component: {{ . }}
{{- end }}
{{- end -}}

{{- define "driftwatch.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .ctx.Chart.Name .ctx.Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{ include "driftwatch.selectorLabels" . }}
app.kubernetes.io/version: {{ .ctx.Chart.AppVersion | quote }}
app.kubernetes.io/part-of: driftwatch
app.kubernetes.io/managed-by: {{ .ctx.Release.Service }}
{{- end -}}

{{/*
Image reference. A digest wins over the tag; an empty tag means the chart's appVersion.
Usage: include "driftwatch.image" (dict "image" .Values.api.image "ctx" $)
*/}}
{{- define "driftwatch.image" -}}
{{- $ref := .image.repository -}}
{{- with .image.registry -}}
{{- $ref = printf "%s/%s" . $ref -}}
{{- end -}}
{{- if .image.digest -}}
{{- printf "%s@%s" $ref .image.digest -}}
{{- else -}}
{{- printf "%s:%s" $ref (default .ctx.Chart.AppVersion .image.tag) -}}
{{- end -}}
{{- end -}}

{{/*
Pod Security "restricted" settings, identical for every workload in the chart and
deliberately not configurable. Usage: include "driftwatch.podSecurityContext" 10001
*/}}
{{- define "driftwatch.podSecurityContext" -}}
runAsNonRoot: true
runAsUser: {{ . }}
runAsGroup: {{ . }}
fsGroup: {{ . }}
fsGroupChangePolicy: OnRootMismatch
seccompProfile:
  type: RuntimeDefault
{{- end -}}

{{- define "driftwatch.containerSecurityContext" -}}
allowPrivilegeEscalation: false
privileged: false
readOnlyRootFilesystem: true
capabilities:
  drop:
    - ALL
{{- end -}}

{{/* Pod spec fields every workload shares. */}}
{{- define "driftwatch.podDefaults" -}}
serviceAccountName: {{ include "driftwatch.serviceAccountName" . }}
automountServiceAccountToken: false
enableServiceLinks: false
{{- with .Values.imagePullSecrets }}
imagePullSecrets:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- end -}}

{{/*
Spread a component's replicas over nodes when possible.
Usage: include "driftwatch.topologySpread" (dict "ctx" $ "component" "api")
*/}}
{{- define "driftwatch.topologySpread" -}}
topologySpreadConstraints:
  - maxSkew: 1
    topologyKey: kubernetes.io/hostname
    whenUnsatisfiable: ScheduleAnyway
    labelSelector:
      matchLabels:
        {{- include "driftwatch.selectorLabels" . | nindent 8 }}
{{- end -}}

{{/* Scheduling fields from a component's values. Usage: include "driftwatch.scheduling" .Values.api */}}
{{- define "driftwatch.scheduling" -}}
{{- with .nodeSelector }}
nodeSelector:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- with .tolerations }}
tolerations:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- with .affinity }}
affinity:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- end -}}

{{/*
Value for one key of a chart-managed Secret, base64-encoded: the explicit value when
set, else the value already stored in the cluster (so upgrades never rotate it), else
a new random one. `helm template` has no cluster, so it always generates.
Usage: include "driftwatch.secretValue" (dict "ctx" $ "name" "x" "key" "k" "value" "" "length" 48)
*/}}
{{- define "driftwatch.secretValue" -}}
{{- if .value -}}
{{- .value | b64enc -}}
{{- else -}}
{{- $existing := lookup "v1" "Secret" .ctx.Release.Namespace .name -}}
{{- $data := dig "data" (dict) $existing -}}
{{- if hasKey $data .key -}}
{{- index $data .key -}}
{{- else -}}
{{- randAlphaNum .length | b64enc -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/* Secret references: name and key, whether chart-managed or existing. */}}

{{- define "driftwatch.enrollmentSecretName" -}}
{{- default (include "driftwatch.componentName" (dict "ctx" . "component" "enrollment")) .Values.enrollment.existingSecret -}}
{{- end -}}

{{- define "driftwatch.enrollmentSecretKey" -}}
{{- if .Values.enrollment.existingSecret -}}{{ .Values.enrollment.existingSecretKey }}{{- else -}}enrollment-token{{- end -}}
{{- end -}}

{{- define "driftwatch.databaseSecretName" -}}
{{- if .Values.postgresql.enabled -}}
{{- default (include "driftwatch.componentName" (dict "ctx" . "component" "postgresql")) .Values.postgresql.auth.existingSecret -}}
{{- else -}}
{{- .Values.externalDatabase.existingSecret -}}
{{- end -}}
{{- end -}}

{{- define "driftwatch.databaseSecretKey" -}}
{{- if not .Values.postgresql.enabled -}}
{{- .Values.externalDatabase.existingSecretKey -}}
{{- else if .Values.postgresql.auth.existingSecret -}}
{{- .Values.postgresql.auth.existingSecretKey -}}
{{- else -}}
password
{{- end -}}
{{- end -}}

{{- define "driftwatch.keycloakSecretName" -}}
{{- default (include "driftwatch.componentName" (dict "ctx" . "component" "keycloak")) .Values.keycloak.admin.existingSecret -}}
{{- end -}}

{{- define "driftwatch.keycloakSecretKey" -}}
{{- if .Values.keycloak.admin.existingSecret -}}{{ .Values.keycloak.admin.existingSecretKey }}{{- else -}}admin-password{{- end -}}
{{- end -}}

{{/* In-cluster service URLs. nginx's resolver does not apply search domains, so they are fully qualified. */}}

{{- define "driftwatch.serviceHost" -}}
{{- printf "%s.%s.svc.cluster.local" (include "driftwatch.componentName" .) .ctx.Release.Namespace -}}
{{- end -}}

{{- define "driftwatch.apiUrl" -}}
{{- printf "http://%s:8080" (include "driftwatch.serviceHost" (dict "ctx" . "component" "api")) -}}
{{- end -}}

{{- define "driftwatch.keycloakUrl" -}}
{{- printf "http://%s:8080" (include "driftwatch.serviceHost" (dict "ctx" . "component" "keycloak")) -}}
{{- end -}}

{{/* OIDC settings, derived from publicUrl and the in-cluster Keycloak unless overridden. */}}

{{- define "driftwatch.oidcIssuer" -}}
{{- default (printf "%s/auth/realms/driftwatch" .Values.publicUrl) .Values.oidc.issuer -}}
{{- end -}}

{{- define "driftwatch.oidcJwksUri" -}}
{{- if .Values.oidc.jwksUri -}}
{{- .Values.oidc.jwksUri -}}
{{- else if .Values.keycloak.enabled -}}
{{- printf "%s/auth/realms/driftwatch/protocol/openid-connect/certs" (include "driftwatch.keycloakUrl" .) -}}
{{- else -}}
{{- printf "%s/protocol/openid-connect/certs" (include "driftwatch.oidcIssuer" .) -}}
{{- end -}}
{{- end -}}

{{- define "driftwatch.jdbcUrl" -}}
{{- if .Values.postgresql.enabled -}}
{{- printf "jdbc:postgresql://%s:5432/%s" (include "driftwatch.serviceHost" (dict "ctx" . "component" "postgresql")) .Values.postgresql.auth.database -}}
{{- else -}}
{{- $url := printf "jdbc:postgresql://%s:%v/%s" .Values.externalDatabase.host .Values.externalDatabase.port .Values.externalDatabase.database -}}
{{- with .Values.externalDatabase.jdbcParameters -}}
{{- $url = printf "%s?%s" $url . -}}
{{- end -}}
{{- $url -}}
{{- end -}}
{{- end -}}

{{- define "driftwatch.databaseUser" -}}
{{- if .Values.postgresql.enabled -}}{{ .Values.postgresql.auth.username }}{{- else -}}{{ .Values.externalDatabase.username }}{{- end -}}
{{- end -}}

{{/*
Cross-field rules the JSON schema cannot express clearly. Rendered from every
workload template so a bad combination fails before anything is applied.
*/}}
{{- define "driftwatch.validate" -}}
{{- if and .Values.enrollment.token (lt (len .Values.enrollment.token) 24) -}}
{{- fail "enrollment.token must be at least 24 characters (the API refuses to start otherwise)" -}}
{{- end -}}
{{- if not .Values.postgresql.enabled -}}
{{- if not .Values.externalDatabase.host -}}
{{- fail "externalDatabase.host is required when postgresql.enabled is false" -}}
{{- end -}}
{{- if not .Values.externalDatabase.existingSecret -}}
{{- fail "externalDatabase.existingSecret is required when postgresql.enabled is false (the chart never stores an external database password)" -}}
{{- end -}}
{{- end -}}
{{- if and (not .Values.keycloak.enabled) (not .Values.oidc.issuer) -}}
{{- fail "oidc.issuer is required when keycloak.enabled is false" -}}
{{- end -}}
{{- end -}}

{{/* NetworkPolicy egress rule for cluster DNS only (port 53, UDP and TCP). */}}
{{- define "driftwatch.dnsEgress" -}}
- to:
    - namespaceSelector:
        {{- toYaml .Values.networkPolicy.dns.namespaceSelector | nindent 8 }}
      podSelector:
        {{- toYaml .Values.networkPolicy.dns.podSelector | nindent 8 }}
  ports:
    - port: 53
      protocol: UDP
    - port: 53
      protocol: TCP
{{- end -}}

{{/*
The realm file shared with compose, with <publicUrl>/* added to the dashboard client's
redirect and post-logout URIs, so sign-in also works where the chart is served on a
URL the file doesn't list (an AWS address, a real hostname). Already listed URLs (the
k3d default) leave the realm unchanged.
*/}}
{{- define "driftwatch.realm" -}}
{{- $realm := .Files.Get "files/keycloak/driftwatch-realm.json" | fromJson -}}
{{- $redirect := printf "%s/*" (trimSuffix "/" .Values.publicUrl) -}}
{{- range $realm.clients -}}
{{- if and (eq .clientId "driftwatch-web") (not (has $redirect .redirectUris)) -}}
{{- $_ := set . "redirectUris" (append .redirectUris $redirect) -}}
{{- $logout := get .attributes "post.logout.redirect.uris" -}}
{{- $_ := set .attributes "post.logout.redirect.uris" (printf "%s##%s" $logout $redirect) -}}
{{- end -}}
{{- end -}}
{{- $realm | mustToPrettyJson -}}
{{- end -}}
