# syntax=docker/dockerfile:1

# Simulated host: a Rocky Linux 9 web server with a partial hardening drop-in.
# Mixed posture; compose enables IP forwarding (net.ipv4.ip_forward=1) as if
# the box also routed traffic for containers.
# Build context: the repository root.
#
#   docker build -f deploy/hosts/rocky-web/Dockerfile -t driftwatch-host-rocky-web:dev .

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

# The Docker Hub library image "rockylinux" stopped receiving updates in 2024;
# the Rocky project publishes rockylinux/rockylinux instead.
FROM rockylinux/rockylinux:9.8
RUN dnf install -y --setopt=install_weak_deps=False openssh-server crontabs \
 && dnf clean all \
 && rm -rf /var/cache/dnf

# 40-web.conf sorts before the vendor's 50-redhat.conf, so it wins wherever
# both set a keyword. It leaves PermitRootLogin at the OpenSSH default
# (prohibit-password) and does not override 50-redhat.conf's X11Forwarding yes.
COPY --chmod=0600 deploy/hosts/rocky-web/40-web.conf /etc/ssh/sshd_config.d/40-web.conf

# The web team set a 90-day expiry but kept Rocky's other aging defaults
# (PASS_MIN_DAYS 0, PASS_WARN_AGE 7). /etc/crontab keeps its stock 0644.
RUN sed -i -E 's/^PASS_MAX_DAYS\s.*/PASS_MAX_DAYS\t90/' /etc/login.defs \
 && grep -Eq '^PASS_MAX_DAYS\s+90$' /etc/login.defs

COPY --from=agent /out/driftwatch-agent /usr/local/bin/driftwatch-agent
ENV DRIFTWATCH_INTERVAL=30s
# The agent runs as PID 1 and as root: it must read /etc/shadow.
ENTRYPOINT ["/usr/local/bin/driftwatch-agent"]
CMD ["run"]
