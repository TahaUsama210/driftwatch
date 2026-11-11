import { randomBytes, randomUUID } from 'node:crypto';
import { expect, test, type APIRequestContext, type Page } from '@playwright/test';

/**
 * The sign-in flow end to end: the dashboard, Keycloak's real login form and the API's
 * token checks, with the demo realm's users. Nothing here touches the demo hosts'
 * data; the decommission test enrolls and removes its own throwaway host.
 */

const ENROLLMENT_TOKEN =
  process.env.DRIFTWATCH_ENROLLMENT_TOKEN ?? 'insecure-local-demo-token-change-me';

// Hosts the viewer must see: compose's demo fleet by default. On k3d they're the node names.
const EXPECTED_HOSTS = (
  process.env.E2E_EXPECT_HOSTS ?? 'ubuntu-hardened,debian-legacy,rocky-web,alpine-drifting'
)
  .split(',')
  .map((name) => name.trim())
  .filter(Boolean);

// The account button's name joins its name and role spans. Chromium puts a space
// between flex items (they are block-level), so allow one before the comma.
const USERS = {
  viewer: { username: 'viewer', password: 'viewer-demo', menu: /^Demo Viewer\s*, Viewer\. Account$/ },
  admin: { username: 'admin', password: 'admin-demo', menu: /^Demo Admin\s*, Admin\. Account$/ },
} as const;

type Role = keyof typeof USERS;

async function signIn(page: Page, role: Role): Promise<void> {
  const user = USERS[role];
  await page.goto('/');
  await expect(page.getByRole('heading', { name: 'Sign in to Driftwatch' })).toBeVisible();
  await page.getByRole('button', { name: 'Sign in' }).click();

  // Keycloak's own login page, served through the dashboard origin under /auth.
  await expect(page).toHaveURL(/\/auth\/realms\/driftwatch\/protocol\/openid-connect\/auth/);
  await page.locator('#username').fill(user.username);
  await page.locator('#password').fill(user.password);
  await page.locator('#kc-login').click();

  await expect(page.getByRole('button', { name: user.menu })).toBeVisible();
  expect(new URL(page.url()).pathname).not.toBe('/signin-callback');
}

const THROWAWAY_HOST_FACTS = {
  os: { id: 'e2e', prettyName: 'Driftwatch browser test' },
  kernel: 'e2e',
  arch: 'amd64',
};

/** Enrolls a host through the dashboard's /api proxy, the same way an agent would. */
async function enrollThrowawayHost(
  request: APIRequestContext,
): Promise<{ hostId: string; hostname: string; agentToken: string }> {
  const hostname = `e2e-${randomBytes(4).toString('hex')}`;
  const enrollment = await request.post('/api/v1/agents/enroll', {
    headers: { Authorization: `Bearer ${ENROLLMENT_TOKEN}` },
    data: {
      machineId: randomBytes(16).toString('hex'),
      agentVersion: 'e2e',
      hostname,
      ...THROWAWAY_HOST_FACTS,
    },
  });
  expect(enrollment.status()).toBe(201);
  const { hostId, agentToken } = (await enrollment.json()) as { hostId: string; agentToken: string };

  expect((await submitScan(request, agentToken, hostname)).status()).toBe(201);
  return { hostId, hostname, agentToken };
}

// The realm's admin service account, used only to clean up after a failed test.
const CLEANUP_CLIENT = {
  id: process.env.E2E_ADMIN_CLIENT_ID ?? 'driftwatch-smoke-admin',
  secret: process.env.E2E_ADMIN_CLIENT_SECRET ?? 'smoke-admin-demo-secret',
};

/** Removes a throwaway host if the test did not get as far as decommissioning it. */
async function removeThrowawayHost(request: APIRequestContext, hostId: string): Promise<void> {
  const token = await request.post('/auth/realms/driftwatch/protocol/openid-connect/token', {
    form: {
      grant_type: 'client_credentials',
      client_id: CLEANUP_CLIENT.id,
      client_secret: CLEANUP_CLIENT.secret,
    },
  });
  expect(token.status(), 'cleanup token request').toBe(200);
  const { access_token: accessToken } = (await token.json()) as { access_token: string };
  const deletion = await request.delete(`/api/v1/hosts/${hostId}`, {
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  expect([204, 404], 'cleanup DELETE status').toContain(deletion.status());
}

function submitScan(request: APIRequestContext, agentToken: string, hostname: string) {
  const now = new Date().toISOString();
  return request.post('/api/v1/scans', {
    headers: { Authorization: `Bearer ${agentToken}` },
    data: {
      scanId: randomUUID(),
      agentVersion: 'e2e',
      startedAt: now,
      finishedAt: now,
      host: { hostname, ...THROWAWAY_HOST_FACTS },
      results: [
        {
          checkId: 'kernel.aslr_enabled',
          category: 'kernel',
          severity: 'high',
          status: 'PASS',
          title: 'Full address space layout randomization is enabled',
        },
      ],
    },
  });
}

test('a visitor sees a sign-in landing and is never redirected without asking', async ({ page }) => {
  await page.goto('/hosts');
  await expect(page.getByRole('heading', { name: 'Sign in to Driftwatch' })).toBeVisible();
  expect(new URL(page.url()).pathname).toBe('/hosts');
});

test('a viewer reads the fleet but is not offered decommission', async ({ page }) => {
  await signIn(page, 'viewer');

  await page.getByRole('navigation', { name: 'Primary' }).getByRole('link', { name: 'Hosts' }).click();
  for (const hostname of EXPECTED_HOSTS) {
    await expect(page.getByRole('link', { name: hostname, exact: true })).toBeVisible();
  }

  const [someHost] = EXPECTED_HOSTS;
  if (!someHost) throw new Error('E2E_EXPECT_HOSTS names no host');
  await page.getByRole('link', { name: someHost, exact: true }).click();
  await expect(page.getByRole('heading', { name: someHost })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Decommission host' })).toHaveCount(0);

  await page.getByRole('button', { name: USERS.viewer.menu }).click();
  await expect(page.getByText('Role:')).toContainText('Viewer');
});

test('tokens stay out of web storage, and a reload restores the session silently', async ({
  page,
}) => {
  await signIn(page, 'viewer');

  const storage = await page.evaluate(() =>
    JSON.stringify({ local: { ...localStorage }, session: { ...sessionStorage } }),
  );
  // Every token Keycloak issues is a JWT, and JWTs start with "eyJ" (base64url of '{"').
  expect(storage).not.toContain('eyJ');

  await page.reload();
  await expect(page.getByRole('button', { name: USERS.viewer.menu })).toBeVisible();
  await expect(page.locator('#kc-login')).toHaveCount(0);
});

test('an admin decommissions a host by typing its hostname, which revokes its agent token', async ({
  page,
  request,
}) => {
  const host = await enrollThrowawayHost(request);
  // A failure part-way must not leave the host in the demo fleet.
  try {
    await signIn(page, 'admin');

    await page.goto(`/hosts/${host.hostId}`);
    await expect(page.getByRole('heading', { name: host.hostname })).toBeVisible();
    await page.getByRole('button', { name: 'Decommission host' }).click();

    const dialog = page.getByRole('dialog', { name: `Decommission ${host.hostname}?` });
    await expect(dialog).toBeVisible();
    const confirm = dialog.getByRole('button', { name: 'Decommission host' });
    await expect(confirm).toBeDisabled();
    await dialog.getByRole('textbox').fill(`${host.hostname}-typo`);
    await expect(confirm).toBeDisabled();
    await dialog.getByRole('textbox').fill(host.hostname);
    await confirm.click();

    await expect(page).toHaveURL(/\/hosts$/);
    await expect(page.getByText(`${host.hostname} decommissioned`)).toBeVisible();
    // Wait for the list itself: while it is still loading no host link exists at all.
    const [someHost] = EXPECTED_HOSTS;
    if (!someHost) throw new Error('E2E_EXPECT_HOSTS names no host');
    await expect(page.getByRole('link', { name: someHost, exact: true })).toBeVisible();
    await expect(page.getByRole('link', { name: host.hostname, exact: true })).toHaveCount(0);

    expect((await submitScan(request, host.agentToken, host.hostname)).status()).toBe(401);
  } finally {
    await removeThrowawayHost(request, host.hostId);
  }
});

test('the Keycloak admin console and master realm are not published on the dashboard origin', async ({
  request,
}) => {
  for (const path of [
    '/auth/admin',
    '/auth/admin/',
    '/auth//admin/master/console/',
    '/auth/%61dmin/',
    '/auth/realms/master',
    '/auth/realms/master/',
    '/auth/realms/master/.well-known/openid-configuration',
  ]) {
    const response = await request.get(path, { maxRedirects: 0 });
    expect(response.status(), path).toBe(404);
  }
  // The application realm stays reachable.
  expect((await request.get('/auth/realms/driftwatch')).status()).toBe(200);
});

test('signing out ends the Keycloak session too', async ({ page }) => {
  await signIn(page, 'admin');

  await page.getByRole('button', { name: USERS.admin.menu }).click();
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByRole('heading', { name: 'Sign in to Driftwatch' })).toBeVisible();

  // With the SSO session gone, signing in again asks for credentials.
  await page.getByRole('button', { name: 'Sign in' }).click();
  await expect(page.locator('#kc-login')).toBeVisible();
});
