#!/bin/sh
# Runs before 20-envsubst-on-templates.sh. The values below are pasted into nginx's
# config and into the JSON body of /config.json, so a quote, `$`, space or newline
# would break (or inject into) either. Refuse to start instead.
set -eu

fail() {
    echo "$0: $1" >&2
    exit 1
}

# matches VALUE REGEX: one line, matching an extended regex end to end.
matches() {
    case "$1" in *'
'*) return 1 ;; esac
    printf '%s\n' "$1" | grep -Eq "$2"
}

upstream_re='^https?://[A-Za-z0-9.-]+(:[0-9]+)?$'
url_re='^https?://[A-Za-z0-9.-]+(:[0-9]+)?(/[A-Za-z0-9._~%/-]*)?$'

matches "${API_UPSTREAM:-}" "$upstream_re" ||
    fail "API_UPSTREAM must be scheme://host[:port], got '${API_UPSTREAM:-}'"
matches "${KEYCLOAK_UPSTREAM:-}" "$upstream_re" ||
    fail "KEYCLOAK_UPSTREAM must be scheme://host[:port], got '${KEYCLOAK_UPSTREAM:-}'"
matches "${DNS_RESOLVER:-}" '^[A-Za-z0-9.:-]+$' ||
    fail "DNS_RESOLVER must be a host or IP address, got '${DNS_RESOLVER:-}'"
matches "${OIDC_AUTHORITY:-}" "$url_re" ||
    fail "OIDC_AUTHORITY must be an http(s) URL without quotes or spaces, got '${OIDC_AUTHORITY:-}'"
matches "${OIDC_CLIENT_ID:-}" '^[A-Za-z0-9._-]+$' ||
    fail "OIDC_CLIENT_ID may only contain letters, digits, '.', '_' and '-', got '${OIDC_CLIENT_ID:-}'"
