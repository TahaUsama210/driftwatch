# Browser tests

Playwright tests that drive the real dashboard in Chromium through Keycloak's real login
form, against a running stack:

```sh
docker compose up -d --build --wait
make e2e        # or: cd e2e && npm ci && npx playwright install chromium && npm test
```

| Test | Proves |
|---|---|
| visitor landing | an anonymous visitor sees "Sign in" and is never redirected without asking |
| viewer | the demo `viewer` signs in, reads every demo host, and is not offered decommission |
| token storage | no JWT lands in `localStorage` or `sessionStorage`; a reload restores the session through the silent renew iframe |
| admin decommission | the demo `admin` must type the exact hostname; the host disappears and its agent token is then rejected with `401` |
| sign-out | signing out ends the Keycloak session, so signing in again asks for credentials |
| admin console | `/auth/admin` and the master realm return 404 at the dashboard origin, including slash and encoding variants |

The decommission test enrolls its own throwaway host through `/api`, so the demo hosts are
never touched; if the test fails part-way, it still removes that host with the smoke
admin client's token. Node 22.22 or newer.

| Variable | Default | Meaning |
|---|---|---|
| `WEB_URL` | `http://localhost:8400` | the public base URL the realm's redirect URIs and the token issuer are built around |
| `DRIFTWATCH_ENROLLMENT_TOKEN` | compose's demo token | enrolls the throwaway host |
| `E2E_EXPECT_HOSTS` | the four compose demo hosts | comma-separated hostnames the viewer must see |
| `E2E_ADMIN_CLIENT_ID`, `E2E_ADMIN_CLIENT_SECRET` | the demo realm's `driftwatch-smoke-admin` | client-credentials client used to clean up the throwaway host |

The same suite runs against the k3d cluster:

```sh
WEB_URL=http://localhost:38080 \
DRIFTWATCH_ENROLLMENT_TOKEN="$(kubectl get secret -n driftwatch-agent driftwatch-enrollment -o jsonpath='{.data.enrollment-token}' | base64 -d)" \
E2E_EXPECT_HOSTS=k3d-driftwatch-server-0,k3d-driftwatch-agent-0,k3d-driftwatch-agent-1 \
npm test
```
