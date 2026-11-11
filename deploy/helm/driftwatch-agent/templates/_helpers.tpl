{{- define "driftwatch-agent.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* "-enrollment" is the longest suffix (11 characters), so the base is cut to 52. */}}
{{- define "driftwatch-agent.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 52 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 52 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 52 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "driftwatch-agent.selectorLabels" -}}
app.kubernetes.io/name: {{ include "driftwatch-agent.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "driftwatch-agent.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{ include "driftwatch-agent.selectorLabels" . }}
app.kubernetes.io/component: agent
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/part-of: driftwatch
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{/* A digest wins over the tag; an empty tag means the chart's appVersion. */}}
{{- define "driftwatch-agent.image" -}}
{{- $ref := .Values.image.repository -}}
{{- with .Values.image.registry -}}
{{- $ref = printf "%s/%s" . $ref -}}
{{- end -}}
{{- if .Values.image.digest -}}
{{- printf "%s@%s" $ref .Values.image.digest -}}
{{- else -}}
{{- printf "%s:%s" $ref (default .Chart.AppVersion .Values.image.tag) -}}
{{- end -}}
{{- end -}}

{{- define "driftwatch-agent.enrollmentSecretName" -}}
{{- default (printf "%s-enrollment" (include "driftwatch-agent.fullname" .)) .Values.enrollment.existingSecret -}}
{{- end -}}

{{- define "driftwatch-agent.enrollmentSecretKey" -}}
{{- if .Values.enrollment.existingSecret -}}{{ .Values.enrollment.existingSecretKey }}{{- else -}}enrollment-token{{- end -}}
{{- end -}}

{{/* The token must match the control plane's, so it is never generated here. */}}
{{- define "driftwatch-agent.validate" -}}
{{- if and .Values.enrollment.token .Values.enrollment.existingSecret -}}
{{- fail "set only one of enrollment.token and enrollment.existingSecret" -}}
{{- end -}}
{{- if not (or .Values.enrollment.token .Values.enrollment.existingSecret) -}}
{{- fail "enrollment.token or enrollment.existingSecret is required (it must match the control plane's enrollment token)" -}}
{{- end -}}
{{- if and .Values.enrollment.token (lt (len .Values.enrollment.token) 24) -}}
{{- fail "enrollment.token must be at least 24 characters" -}}
{{- end -}}
{{- end -}}
