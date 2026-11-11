# 5. Same-origin OIDC with Keycloak, enforced by the API

- Status: accepted
- Date: 2026-11-09

## Context

Phase 1 left the read API and the dashboard unauthenticated. Phase 2 needs dashboard
users with two roles: viewers read, admins can also decommission hosts. Agents already
have their own credentials (ADR 2), and those must not mix with user credentials. The
SPA runs in a browser, so it can't keep a client secret.

## Decision

- **Keycloak** is the identity provider, with one realm file shared by compose and the
  Helm chart. The dashboard is a public client using Authorization Code with PKCE (S256);
  implicit flow and password grants are off.
- **Same origin.** Keycloak is served under `/auth` behind the dashboard's nginx, so the
  SPA, the IdP and the API share one origin. There's no CORS to configure, the CSP stays
  `'self'`, and silent renew works in an iframe without third-party cookies. `KC_HOSTNAME`
  is fixed, so the token issuer is the same however Keycloak is reached. nginx returns
  404 for `/auth/admin/` and the master realm, so the admin console isn't public.
- **The API is the enforcement point.** It's an OAuth2 resource server that checks
  signature (RS256 only), `exp`, the exact issuer, an `aud` of `driftwatch-api` and a
  `sub`, then maps `realm_access.roles` to authorities. The UI hides admin actions for
  viewers only as a convenience.
- **Separate security chains** for dashboard, agent and public endpoints: a JWT is never
  accepted on agent endpoints, and an agent token never on dashboard endpoints.
- Signing keys are fetched **lazily** and cached, so the API starts and stays ready while
  Keycloak is down. A request whose key can't be fetched gets `503`, not `401`, since the
  token may be valid.
- Tokens live **in memory only** in the SPA; a reload restores the session through the
  silent renew iframe.

## Consequences

- Clients get the standard semantics: `401` with `WWW-Authenticate: Bearer` means get a
  new token, `403` means the role is missing, and `503` means retry.
- Keeping tokens out of web storage means an injected script finds no refresh token
  there. The cost is a silent-renew round trip on every page load.
- The demo runs Keycloak in dev mode (embedded H2) with demo passwords and secrets. That
  is fine on localhost, but a real deployment needs Keycloak's production mode, a
  database and real secrets.
- An IdP on a different origin would need its origin added to the CSP and a CORS policy
  on the API.
