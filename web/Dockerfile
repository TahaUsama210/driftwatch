# Driftwatch dashboard: build the SPA with Node, serve it with unprivileged nginx.
# Build context is web/:  docker build -t driftwatch-web web

# ---- build ------------------------------------------------------------------
# The output is static files, identical for every architecture, so this stage always
# runs natively on the build machine; only the runtime stage is per-platform.
FROM --platform=$BUILDPLATFORM node:22.23.4-alpine3.24 AS build
WORKDIR /app

# Dependencies first, so source edits reuse this layer.
COPY package.json package-lock.json .npmrc ./
RUN npm ci --no-audit --no-fund

COPY . .
RUN npm run build

# ---- runtime ----------------------------------------------------------------
FROM nginxinc/nginx-unprivileged:1.31.7-alpine3.24

# Pick up Alpine security fixes released since the base image was built (the CI image
# scan fails on fixable HIGH/CRITICAL CVEs); the base tag itself is bumped by Dependabot.
USER root
RUN apk upgrade --no-cache

# API_UPSTREAM / KEYCLOAK_UPSTREAM: where /api/ (+ /actuator/health) and /auth/ go.
# DNS_RESOLVER: Docker's embedded DNS; override when running outside Docker networks.
# OIDC_AUTHORITY / OIDC_CLIENT_ID: rendered into /config.json for the SPA.
ENV API_UPSTREAM=http://api:8080 \
    KEYCLOAK_UPSTREAM=http://keycloak:8080 \
    DNS_RESOLVER=127.0.0.11 \
    OIDC_AUTHORITY=http://localhost:8400/auth/realms/driftwatch \
    OIDC_CLIENT_ID=driftwatch-web \
    NGINX_ENVSUBST_FILTER="^(API_UPSTREAM|KEYCLOAK_UPSTREAM|DNS_RESOLVER|OIDC_AUTHORITY|OIDC_CLIENT_ID)$"

COPY --chmod=0755 nginx/entrypoint/15-driftwatch-check-env.sh /docker-entrypoint.d/
COPY nginx/snippets/ /etc/nginx/snippets/
COPY nginx/templates/ /etc/nginx/templates/
COPY --from=build /app/dist /usr/share/nginx/html

# The base image already runs as nginx (uid 101); stated here so it is explicit.
# At runtime it writes only to /tmp and /etc/nginx/conf.d (see README).
USER 101
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:8080/healthz || exit 1
