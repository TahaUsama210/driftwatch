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
Unknown routes, hosts and checks get a not-found page inside the app shell.

## Requirements

Node **22.22.2 or newer** (`.nvmrc` pins 22.23.4, which CI uses). Vite 8, Vitest 5,
jsdom 30 and React Router 8 all need a recent Node 22 patch; `.npmrc` sets
`engine-strict`, so an older Node fails at `npm ci` with a clear message instead of
later with an obscure one.

## Scripts

| Script               | What it does                                                                                                       |
| -------------------- | ------------------------------------------------------------------------------------------------------------------ |
| `npm run dev`        | Dev server on <http://localhost:5180>, proxying `/api` and `/actuator` to the API on `http://localhost:8480`       |
| `npm run dev:mock`   | Same dev server, with the API served in the browser by MSW (no backend needed)                                     |
| `npm run build`      | Type-check (`tsc -b`), then production build into `dist/`                                                          |
| `npm run preview`    | Serve `dist/` on port 5181 with the same API proxy                                                                 |
| `npm run lint`       | ESLint (zero warnings allowed), then `prettier --check`                                                            |
| `npm run typecheck`  | `tsc -b` over app and tooling configs                                                                              |
| `npm test`           | Vitest, single run                                                                                                 |
| `npm run test:watch` | Vitest in watch mode                                                                                               |
| `npm run coverage`   | Vitest with V8 coverage (text + HTML + lcov in `coverage/`); fails below the thresholds in `vite.config.ts`        |
| `npm run gen:api`    | Regenerate `src/api/schema.d.ts` from `../contracts/openapi.yaml` (the file is committed; CI fails if it is stale) |
| `npm run format`     | Prettier, write mode                                                                                               |

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

The worker script is served from `node_modules/msw` by a small Vite plugin in mock mode
only, so it always matches the installed MSW version and never ships in `dist/`.

## Structure

```
src/
  api/          generated contract types, typed client, ApiError, query keys
  app/          routes, app shell, theme toggle, query client defaults
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

`docker build -t driftwatch-web web` builds the app on `node:22.23.4-alpine3.24` and
serves it from `nginxinc/nginx-unprivileged:1.30.6-alpine3.24` on port 8080 as uid 101.
It needs no Linux capabilities.

| Variable       | Default           | Meaning                                                              |
| -------------- | ----------------- | -------------------------------------------------------------------- |
| `API_UPSTREAM` | `http://api:8080` | Where `/api/` and `/actuator/health` (and its sub-paths) are proxied |
| `DNS_RESOLVER` | `127.0.0.11`      | Resolver for `API_UPSTREAM`; Docker's embedded DNS by default        |

The upstream is resolved per request, so the container starts before the API exists
and follows it across restarts. If the API cannot be reached, `/api/` answers `502`
with `application/problem+json`, so the dashboard shows a readable error. Every other
path under `/actuator/` returns 404.

Deep links such as `/hosts/<id>` receive `index.html`. Hashed files under `/assets/`
are served with `Cache-Control: public, max-age=31536000, immutable` (not on 404s);
`index.html` and other unhashed files get `no-cache`. Text responses over 1 KiB are
gzip-compressed. Responses carry a Content-Security-Policy that allows only same-origin resources (no
`unsafe-inline`), plus `frame-ancestors 'none'`, `X-Content-Type-Options`,
`Referrer-Policy`, `X-Frame-Options`, `Permissions-Policy` and
`Cross-Origin-Opener-Policy`. `/healthz` is a local liveness endpoint used by the image
`HEALTHCHECK`.

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
