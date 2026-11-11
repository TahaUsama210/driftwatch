# Driftwatch web

The Driftwatch dashboard: fleet compliance posture, per-host score history, and what
changed between scans. A React single-page app that reads the API described in
[`contracts/openapi.yaml`](../contracts/openapi.yaml); domain rules (statuses, scoring,
drift directions) come from [`docs/CONTRACTS.md`](../docs/CONTRACTS.md).

| Screen      | Route                         | What it shows                                                                                         |
| ----------- | ----------------------------- | ----------------------------------------------------------------------------------------------------- |
| Overview    | `/`                           | KPI tiles, fleet score trend (1h / 24h / 7d / 30d), top failing checks, live drift feed               |
| Hosts       | `/hosts`                      | Searchable, filterable, sortable, paginated host table; all state in the query string                 |
| Host detail | `/hosts/:hostId`              | Host facts, score history, drift between any two scans, latest results by category, the host's events |
| Checks      | `/checks`, `/checks/:checkId` | Catalog with a per-check status bar; per-host status and evidence, failures first                     |
| Events      | `/events`                     | Drift events filtered by direction (and by host via `?hostId=`)                                       |

With no enrolled hosts, the overview and hosts pages explain how to run the agent.
Unknown routes, hosts and checks get a not-found page inside the app shell. Everything
is behind sign-in (OIDC with Keycloak); admins can also decommission a host.

## Requirements

Node **22.22.2 or newer** (`.nvmrc` pins 22.23.4, which CI uses). Vite 8, Vitest 5,
jsdom 30 and React Router 8 all need a recent Node 22 patch; `.npmrc` sets
`engine-strict`, so an older Node fails at `npm ci` with a clear message instead of
later with an obscure one.

## Scripts

| Script               | What it does                                                                                                                             |
| -------------------- | ---------------------------------------------------------------------------------------------------------------------------------------- |
| `npm run dev`        | Dev server on <http://localhost:5180>, proxying `/api` and `/actuator` to `http://localhost:8480`; signs in against the compose Keycloak |
| `npm run dev:mock`   | Same dev server, with the API served in the browser by MSW (no backend needed)                                                           |
| `npm run build`      | Type-check (`tsc -b`), then production build into `dist/`                                                                                |
| `npm run preview`    | Serve `dist/` on port 5181 with the same API proxy                                                                                       |
| `npm run lint`       | ESLint (zero warnings allowed), then `prettier --check`                                                                                  |
| `npm run typecheck`  | `tsc -b` over app and tooling configs                                                                                                    |
| `npm test`           | Vitest, single run                                                                                                                       |
| `npm run test:watch` | Vitest in watch mode                                                                                                                     |
| `npm run coverage`   | Vitest with V8 coverage (text + HTML + lcov in `coverage/`); fails below the thresholds in `vite.config.ts`                              |
| `npm run gen:api`    | Regenerate `src/api/schema.d.ts` from `../contracts/openapi.yaml` (the file is committed; CI fails if it is stale)                       |
| `npm run format`     | Prettier, write mode                                                                                                                     |

## Mock mode

`npm run dev:mock` starts an MSW service worker that answers every read endpoint of
the contract from an in-memory model of the demo fleet (`src/mocks/fleet.ts`): the four
hosts from `CONTRACTS.md` (`host-ubuntu-hardened`, `host-debian-legacy`,
`host-rocky-web`, `host-alpine-drifting`) plus `host-fedora-batch`, which stopped
reporting three hours ago so the stale state has something to show.

Scans are not stored. A host's scan _k_ finishes at `firstScan + k × interval`, and each
check's status at that moment comes from the host's posture timeline (a baseline, dated
changes, and on the Alpine host a `PermitRootLogin` check that flips every four
minutes). Scores, drift reports, events and trend buckets are derived from that with
the contract's rules, so the dashboard keeps receiving new scans and drift events as
time passes, exactly as it would from live agents. Responses get 150 to 400 ms of
latency so loading states are visible.

Append a scenario to the first URL you open:

- `?scenario=empty`: no hosts enrolled (shows the enrollment instructions)
- `?scenario=error`: every API call returns `503` problem details (shows error states)

Mock mode skips the identity provider and signs in a demo user. Pick the role on the
first URL with `?role=admin` (the default), `?role=viewer` or `?role=none` (an account
with no Driftwatch role, to see the "insufficient permissions" page), or switch between
admin and viewer later in the user menu. The mock user's requests carry a fake bearer
token (`mock.<roles>`) and the MSW handlers enforce the API's authorization matrix with
it: no token is `401`, a viewer's `DELETE` is `403`. Tests run against the same handlers.
Signing out shows the landing page; signing in again restores the demo user.

The worker script is served from `node_modules/msw` by a small Vite plugin in mock mode
only, so it always matches the installed MSW version and never ships in `dist/`.

## Authentication

Dashboard login is OpenID Connect Authorization Code with PKCE (S256), implemented with
`oidc-client-ts` against the `driftwatch` Keycloak realm (public client
`driftwatch-web`). Keycloak is served under `/auth` on the dashboard's own origin, so
the SPA, the identity provider and the API share one origin: no CORS, and the silent
sign-in iframe needs no third-party cookies.

1. **Startup.** The app fetches `GET /config.json` (`{"oidc":{"authority","clientId"}}`).
   If that fails it shows a configuration error with a retry, and never guesses.
2. **Session restore.** It tries a silent `prompt=none` sign-in in a hidden iframe that
   lands on `/silent-renew.html`. A live Keycloak session signs the user straight in;
   otherwise the signed-out landing appears. Sign-in only ever starts from the **Sign in**
   button, so a broken identity provider cannot cause a redirect loop.
3. **Sign-in.** The browser goes to Keycloak and returns to `/signin-callback`; the code
   is redeemed, removed from the address bar, and the user lands on the page they
   started from (`state` carries a same-origin path only, so it is no open redirect).
4. **Using the API.** Every request carries `Authorization: Bearer <access token>`,
   attached only for this origin. Tokens last 300 s and are renewed a minute early with
   the refresh token. If the API still answers `401` (session revoked, Keycloak
   restarted), the client renews once and replays the request; if that fails too it
   sends the user to sign in again, and returns to the same page. If the API rejects a
   fresh session within 60 s of the last redirect, it stops and shows "Sign in again"
   instead of looping. A `403` shows an "Insufficient permissions" state, and a user with
   no Driftwatch role gets a full-page explanation instead of the app.
5. **Roles.** The user menu shows the name and role (`realm_access.roles` from the access
   token, where `admin` includes `viewer`). Admins see **Decommission host** on host pages:
   a dialog explains what is deleted and asks for the hostname to be typed. Success
   returns to the host list with a confirmation. The button is hidden for viewers, but
   that is a convenience: the API checks the role on every request.
6. **Sign-out** ends the Keycloak session (end-session endpoint with `id_token_hint`)
   and comes back to the landing page. Each signed-in user gets a fresh query cache, so
   no data carries over to the next account.

**Token storage.** Access, refresh and ID tokens are kept **in memory only**. They are
never in `localStorage` or `sessionStorage`. Script injected into the page (XSS) could
read web storage in one line, and would find a refresh token there that stays valid for
hours. In memory, nothing is at rest to scrape. This does not make XSS harmless: injected
code could still make API calls as the user while the tab is open. The strict CSP (no
inline script, same-origin only) is the defence against that. The cost of in-memory
tokens is that a reload loses them. The silent iframe sign-in gets them back in one
same-origin round trip while the Keycloak session lasts (30 min idle, 10 h max), so a
reload does not ask for the password again. `sessionStorage` holds only the one-time
PKCE verifier and `state` while the browser is at Keycloak, because they must survive
that navigation. The library would put them in `localStorage`. They are deleted at the
callback.

**Local demo accounts** (from the realm import; for the localhost demo only):
`viewer` / `viewer-demo` (read-only) and `admin` / `admin-demo` (can decommission).
With `npm run dev`, sign in through the compose stack's Keycloak at
`http://localhost:8400/auth/realms/driftwatch`. Port 5180 is a registered redirect URI
and web origin, so the cross-origin dev setup works. `OIDC_AUTHORITY` and
`OIDC_CLIENT_ID` in the environment override what the dev server puts in
`/config.json`. `npm run preview` (5181) serves the same file, but 5181 is not a
registered redirect URI.

## Structure

```
src/
  api/          generated contract types, typed client, ApiError, query keys
  app/          startup, routes, app shell, theme toggle, query client defaults
  auth/         OIDC and mock auth controllers, auth gate, landing, user menu, roles
  components/   design-system pieces: badges, cards, fields, states, charts
  domain/       contract rules in one place: score tiers, statuses, severities, directions
  features/     one folder per screen, each with its data hooks (api.ts) and tests
  lib/          formatting (Intl), shared clock, theme store
  mocks/        demo-fleet model and MSW handlers (dev:mock and tests)
  styles/       Tailwind entry point and design tokens
  test/         Vitest setup, MSW server, render helper, fixture builders
```

## Design decisions

**Data access.** Every request goes through the `openapi-fetch` client typed by the
generated schema, and through `unwrap()`, which turns non-2xx responses into an
`ApiError` carrying the RFC 9457 problem (or a synthesised one for network failures and
non-problem bodies). Each feature exposes hooks (`useFleetSummary`, `useHosts`,
`useHostDrift`, ...) built on keys from `api/queryKeys.ts`. 4xx responses are never
retried; other failures retry twice.

**Live data.** Fleet, host, check and event queries poll every 15 seconds while the tab
is visible. Scan results never change once stored and are not refetched; the drift
panel polls only while it compares against the latest scan (no `?to=` pinned). When a
host reports a new scan, the previous results stay on screen until the new ones load.
A failed background refresh never replaces data already on screen; the overview shows
"Updated … ago", a manual refresh, and a "Refresh failed" marker when that happens.
Range and filter changes keep the previous result visible (dimmed) instead of flashing
a skeleton.

**URL state.** Host list filters, sort and page; the trend range; the checks category;
the events direction and host; and the two scans the drift panel compares all live in
the query string, so any view can be shared or bookmarked.

**Design system.** Colours, fonts and surfaces are CSS custom properties declared once
in `styles/index.css`; Tailwind's default palette is removed so components can only use
these roles. Dark mode follows the OS, and the header toggle (System / Light / Dark)
overrides it in either direction. The choice is stored in `localStorage` (failures are
tolerated) and applied before first paint by `public/theme-init.js`. Status colours are
reserved for state and always come with an icon and a text label. Score bands are
defined once in `domain/score.ts`: 90 and above is good, 70 to below 90 needs attention,
below 70 is poor. Severity always appears as ascending pips plus its name.

**Charts.** Recharts, loaded only by the pages that draw charts. The score charts are a
single series (so no legend) with a 2 px line, a 10 % area wash, hairline grid, a
crosshair tooltip, and a table view toggle as an accessible alternative. Host history
uses a step line because a score holds until the next scan. The per-check status bar is
a thin segmented bar with a single legend per page and an accessible name listing every
count.

**Accessibility.** Landmarks, a skip link, focus moved to the page on navigation,
visible focus rings, labelled controls (segmented controls are native radio groups),
`aria-sort` on sortable headers, and wide tables that scroll inside their container so
the page itself never scrolls sideways at 375 px.

**Time.** Relative times use `Intl.RelativeTimeFormat` and update from one shared
one-second clock; the absolute timestamp (with time zone) is in the `title` and
`dateTime` attributes.

## Container image

`docker build -t driftwatch-web web` builds the static app on `node:22.23.4-alpine3.24`.
That stage is `FROM --platform=$BUILDPLATFORM`, because the output is the same for every
architecture, so multi-arch builds compile once, natively. The app is served from
`nginxinc/nginx-unprivileged:1.30.6-alpine3.24` on port 8080 as uid 101, with Alpine
packages upgraded at build time so fixable CVEs are not shipped. It needs no Linux
capabilities.

| Variable            | Default                                        | Meaning                                                              |
| ------------------- | ---------------------------------------------- | -------------------------------------------------------------------- |
| `API_UPSTREAM`      | `http://api:8080`                              | Where `/api/` and `/actuator/health` (and its sub-paths) are proxied |
| `KEYCLOAK_UPSTREAM` | `http://keycloak:8080`                         | Where `/auth/` is proxied                                            |
| `DNS_RESOLVER`      | `127.0.0.11`                                   | Resolver for the upstreams; Docker's embedded DNS by default         |
| `OIDC_AUTHORITY`    | `http://localhost:8400/auth/realms/driftwatch` | Issuer written to `/config.json`                                     |
| `OIDC_CLIENT_ID`    | `driftwatch-web`                               | Client ID written to `/config.json`                                  |

These values are written into nginx's config and into a JSON body, so
`/docker-entrypoint.d/15-driftwatch-check-env.sh` checks them before rendering:
plain `scheme://host[:port]` upstreams, and an issuer URL and client ID with no quotes,
spaces, `$` or newlines. Anything else stops the container with a message, rather than
producing a broken or injectable config.

Upstreams are resolved per request, so the container starts before the API or Keycloak
exist and follows them across restarts. If either cannot be reached, `/api/` or
`/auth/` answers `502` with `application/problem+json`.

- `/config.json` is rendered from the environment at start and sent with
  `Cache-Control: no-store`.
- `/auth/` goes to Keycloak with `X-Forwarded-For/Proto/Host/Port`. Values an ingress
  in front already set (Traefik in k3d) are kept; otherwise they describe the browser's
  request. Proxy buffers are 16k because Keycloak's cookies are large, and so are client
  header buffers. The SPA's security headers are **not** added to Keycloak's responses,
  which carry their own. `/auth/admin` and `/auth/realms/master`, bare or with any path
  below them, return 404: the admin console and the master realm are not published on
  the dashboard's origin.
- Deep links such as `/hosts/<id>` and `/signin-callback` receive `index.html`. Hashed
  files under `/assets/` get `Cache-Control: public, max-age=31536000, immutable` (not
  on 404s); `index.html` and other unhashed files get `no-cache`. Text responses over
  1 KiB are gzip-compressed. `/healthz` is a local liveness endpoint used by the image
  `HEALTHCHECK`.

**Security headers** on the app's own responses: a Content-Security-Policy that allows
only same-origin resources (no `unsafe-inline`), with `connect-src 'self'` for the
discovery and token calls, `frame-src 'self'` for the silent sign-in iframe and
`form-action 'self'`. Also `X-Content-Type-Options`, `Referrer-Policy`,
`Permissions-Policy` and `Cross-Origin-Opener-Policy`. Framing is refused
(`frame-ancestors 'none'`, `X-Frame-Options: DENY`) everywhere except
`/silent-renew.html`, which only this origin may frame. An identity provider on another
origin would need its origin added to `connect-src` and `frame-src`. The contract puts
it on the same origin.

**Read-only root filesystem.** nginx writes only to these two paths. Mount an
`emptyDir` (Kubernetes) or tmpfs on each:

| Path                | Written by                                                              |
| ------------------- | ----------------------------------------------------------------------- |
| `/tmp`              | nginx pid file and its client-body and proxy temp directories           |
| `/etc/nginx/conf.d` | the entrypoint, which renders `default.conf` from the template at start |

Both must be writable by uid 101. A Kubernetes `emptyDir` already is. With Docker, give
the tmpfs that owner:

```
docker run --read-only --cap-drop ALL --security-opt no-new-privileges \
  --tmpfs /tmp:uid=101,gid=101 --tmpfs /etc/nginx/conf.d:uid=101,gid=101 \
  -p 127.0.0.1:8400:8080 driftwatch-web
```

## Toolchain notes

- **TypeScript 5.9.** typescript-eslint 8.72 supports TypeScript below 6.1 and
  openapi-typescript 7.13 requires 5.x, so TypeScript 7 is not usable with this lint
  and codegen setup yet.
- **ESLint 10** with `eslint-plugin-jsx-a11y` 6.10, whose peer range stops at ESLint 9.
  The plugin uses none of the APIs ESLint 10 removed; `overrides` in `package.json`
  lets it resolve against ESLint 10.
- **MSW 2.x**, as the project brief specifies.
- `npm install` with npm 10 can fail with `Cannot read properties of null (reading
'edgesOut')` (an npm bug with Vitest's optional peer dependencies). The lockfile was
  produced with `npx npm@11 install`; `npm ci` works with npm 10.
