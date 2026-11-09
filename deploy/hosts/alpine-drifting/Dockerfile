# syntax=docker/dockerfile:1

# Simulated host: a compliant Alpine 3.22 box whose PermitRootLogin flips
# between "no" and "yes" every DRIFT_PERIOD seconds, so drift events appear
# live on the dashboard.
# Build context: the repository root.
#
#   docker build -f deploy/hosts/alpine-drifting/Dockerfile -t driftwatch-host-alpine-drifting:dev .

FROM golang:1.27-alpine3.24 AS agent
WORKDIR /src
COPY agent/go.mod ./
RUN go mod download
COPY agent/ ./
ARG VERSION=dev
ARG COMMIT=unknown
RUN --mount=type=cache,target=/root/.cache/go-build \
    CGO_ENABLED=0 go build -trimpath \
      -ldflags "-s -w -X main.version=${VERSION} -X main.commit=${COMMIT}" \
      -o /out/driftwatch-agent ./cmd/driftwatch-agent

FROM alpine:3.22
# shadow provides /etc/login.defs; tini runs as PID 1 because the entrypoint
# keeps a background process alongside the agent.
RUN apk add --no-cache openssh-server shadow tini

# The stock sshd_config includes sshd_config.d/*.conf before its own settings.
# 00-drift.conf, rewritten by the entrypoint, sorts first and so decides
# PermitRootLogin; 10-hardening.conf covers the rest.
COPY --chmod=0600 deploy/hosts/alpine-drifting/10-hardening.conf /etc/ssh/sshd_config.d/10-hardening.conf
COPY --chmod=0644 deploy/hosts/alpine-drifting/login.defs /etc/login.defs
COPY --chmod=0755 deploy/hosts/alpine-drifting/drift-entrypoint.sh /usr/local/bin/drift-entrypoint
RUN chmod 0600 /etc/ssh/sshd_config \
 && printf 'PermitRootLogin no\n' > /etc/ssh/sshd_config.d/00-drift.conf \
 && chmod 0600 /etc/ssh/sshd_config.d/00-drift.conf

COPY --from=agent /out/driftwatch-agent /usr/local/bin/driftwatch-agent
ENV DRIFTWATCH_INTERVAL=30s \
    DRIFT_PERIOD=120
# tini forwards SIGTERM to the agent and reaps the drift loop. The agent runs
# as root: it must read /etc/shadow.
ENTRYPOINT ["/sbin/tini", "--", "/usr/local/bin/drift-entrypoint"]
CMD ["run"]
