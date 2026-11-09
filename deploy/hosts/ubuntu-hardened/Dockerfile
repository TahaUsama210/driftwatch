# syntax=docker/dockerfile:1

# Simulated host: a hardened Ubuntu 24.04 server where nearly every check passes.
# Build context: the repository root.
#
#   docker build -f deploy/hosts/ubuntu-hardened/Dockerfile -t driftwatch-host-ubuntu-hardened:dev .

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

FROM ubuntu:24.04
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends openssh-server cron \
 && rm -rf /var/lib/apt/lists/*

# The stock sshd_config includes sshd_config.d/*.conf before its own settings,
# so this drop-in decides every keyword it sets (including X11Forwarding, which
# the stock file enables further down). ClientAliveInterval is left unset: the
# one deliberate gap on this host.
COPY --chmod=0600 deploy/hosts/ubuntu-hardened/10-hardening.conf /etc/ssh/sshd_config.d/10-hardening.conf

RUN chmod 0600 /etc/ssh/sshd_config /etc/crontab \
 && sed -i -E \
      -e 's/^PASS_MAX_DAYS\s.*/PASS_MAX_DAYS\t365/' \
      -e 's/^PASS_MIN_DAYS\s.*/PASS_MIN_DAYS\t1/' \
      -e 's/^PASS_WARN_AGE\s.*/PASS_WARN_AGE\t14/' \
      /etc/login.defs \
 && grep -Eq '^PASS_MAX_DAYS\s+365$' /etc/login.defs \
 && grep -Eq '^PASS_MIN_DAYS\s+1$' /etc/login.defs \
 && grep -Eq '^PASS_WARN_AGE\s+14$' /etc/login.defs

COPY --from=agent /out/driftwatch-agent /usr/local/bin/driftwatch-agent
ENV DRIFTWATCH_INTERVAL=30s
# The agent runs as PID 1 and as root: it must read /etc/shadow.
ENTRYPOINT ["/usr/local/bin/driftwatch-agent"]
CMD ["run"]
