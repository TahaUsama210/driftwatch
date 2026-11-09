# syntax=docker/dockerfile:1

# Simulated host: a long-lived Debian 12 box that predates the hardening
# baseline. Weak SSH settings, no password aging, and a second UID 0 account.
# Build context: the repository root.
#
#   docker build -f deploy/hosts/debian-legacy/Dockerfile -t driftwatch-host-debian-legacy:dev .

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

# Pinned to a point release (debian:12 moves with every one).
FROM debian:12.14
RUN apt-get update \
 && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends openssh-server cron \
 && rm -rf /var/lib/apt/lists/*

# Settings an admin uncommented in the main sshd_config years ago. The stock
# file already enables X11Forwarding and is world-readable (0644); both stay.
# login.defs keeps Debian's defaults (PASS_MAX_DAYS 99999, PASS_MIN_DAYS 0).
RUN sed -i -E \
      -e 's/^#?PermitRootLogin\s.*/PermitRootLogin yes/' \
      -e 's/^#?PasswordAuthentication\s.*/PasswordAuthentication yes/' \
      -e 's/^#?MaxAuthTries\s.*/MaxAuthTries 10/' \
      -e 's/^#?LoginGraceTime\s.*/LoginGraceTime 2m/' \
      /etc/ssh/sshd_config \
 && grep -qx 'PermitRootLogin yes' /etc/ssh/sshd_config \
 && grep -qx 'PasswordAuthentication yes' /etc/ssh/sshd_config \
 && grep -qx 'MaxAuthTries 10' /etc/ssh/sshd_config \
 && grep -qx 'LoginGraceTime 2m' /etc/ssh/sshd_config \
 && useradd --non-unique --uid 0 --gid 0 --no-create-home --home-dir /root \
      --shell /bin/bash --comment "legacy admin" toor

COPY --from=agent /out/driftwatch-agent /usr/local/bin/driftwatch-agent
ENV DRIFTWATCH_INTERVAL=30s
# The agent runs as PID 1 and as root: it must read /etc/shadow.
ENTRYPOINT ["/usr/local/bin/driftwatch-agent"]
CMD ["run"]
