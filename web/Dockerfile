# Driftwatch dashboard: build the SPA with Node, serve it with unprivileged nginx.
# Build context is web/:  docker build -t driftwatch-web web

# ---- build ------------------------------------------------------------------
FROM node:22.23.4-alpine3.24 AS build
WORKDIR /app

# Dependencies first, so source edits reuse this layer.
COPY package.json package-lock.json .npmrc ./
RUN npm ci --no-audit --no-fund

COPY . .
RUN npm run build

# ---- runtime ----------------------------------------------------------------
FROM nginxinc/nginx-unprivileged:1.30.6-alpine3.24

# API_UPSTREAM: where /api/ and /actuator/health are proxied.
# DNS_RESOLVER: Docker's embedded DNS; override when running outside Docker networks.
ENV API_UPSTREAM=http://api:8080 \
    DNS_RESOLVER=127.0.0.11 \
    NGINX_ENVSUBST_FILTER="^(API_UPSTREAM|DNS_RESOLVER)$"

COPY nginx/snippets/ /etc/nginx/snippets/
COPY nginx/templates/ /etc/nginx/templates/
COPY --from=build /app/dist /usr/share/nginx/html

# The base image already runs as nginx (uid 101); stated here so it is explicit.
USER 101
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
