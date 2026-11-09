# syntax=docker/dockerfile:1

# Build context: agent/
#
#   docker build -t driftwatch-agent:dev --build-arg VERSION=0.1.0 --build-arg COMMIT=$(git rev-parse --short HEAD) agent
#
# The image scans the host it runs on when the host's root is mounted into it:
#
#   docker run --rm -v /:/host:ro -e DRIFTWATCH_ROOT=/host -e DRIFTWATCH_PROC=/host/proc \
#     driftwatch-agent:dev scan

FROM golang:1.27-alpine3.24 AS build
WORKDIR /src
COPY go.mod ./
RUN go mod download
COPY . .
ARG VERSION=dev
ARG COMMIT=unknown
RUN --mount=type=cache,target=/root/.cache/go-build \
    CGO_ENABLED=0 go build -trimpath \
      -ldflags "-s -w -X main.version=${VERSION} -X main.commit=${COMMIT}" \
      -o /out/driftwatch-agent ./cmd/driftwatch-agent

# Distroless publishes no version tags, so the digest pins the exact build of
# static-debian13. The :latest flavour (not :nonroot) is deliberate, see below.
FROM gcr.io/distroless/static-debian13@sha256:448810357cc8e67b7208988abdba0f5ec3d3aebee0a5b13eb624979792cffc0f
COPY --from=build /out/driftwatch-agent /usr/local/bin/driftwatch-agent
# Runs as root by necessity: the accounts.* checks read /etc/shadow, which only
# root can read, and the stat-based checks must see the host's real ownership.
# Mount the host root read-only (-v /:/host:ro) so the agent cannot change it.
USER 0:0
ENTRYPOINT ["/usr/local/bin/driftwatch-agent"]
CMD ["run"]
