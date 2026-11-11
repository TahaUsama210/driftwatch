import { screen, waitFor, within } from '@testing-library/react';
import { http } from 'msw';
import { beforeEach, describe, expect, it } from 'vitest';
import { mockAccessToken } from '../../../auth/mockToken';
import { createDemoFleet } from '../../../mocks/fleet';
import { createHandlers, problemResponse } from '../../../mocks/handlers';
import { renderRoute } from '../../../test/render';
import { server, TEST_NOW } from '../../../test/server';

const HOST_ID = 'a91d4e07-2b6c-4f38-8e5a-c7b30f19d264';
const HOSTNAME = 'host-debian-legacy';

/** The real handlers over a fresh fleet, recording every DELETE and its bearer token. */
function serveFreshFleet() {
  const deletes: { url: string; authorization: string | null }[] = [];
  server.events.on('request:start', ({ request }) => {
    if (request.method === 'DELETE') {
      deletes.push({ url: request.url, authorization: request.headers.get('Authorization') });
    }
  });
  server.use(...createHandlers(createDemoFleet({ anchor: TEST_NOW, now: () => TEST_NOW })));
  return deletes;
}

beforeEach(() => {
  server.events.removeAllListeners();
});

describe('decommissioning a host', () => {
  it('lets an admin decommission after typing the hostname, then returns to the hosts list', async () => {
    const deletes = serveFreshFleet();
    const { user, router } = renderRoute(`/hosts/${HOST_ID}`, { role: 'admin' });

    await user.click(await screen.findByRole('button', { name: 'Decommission host' }));
    const dialog = screen.getByRole('dialog', { name: `Decommission ${HOSTNAME}?` });
    expect(dialog).toHaveAccessibleDescription(/permanently deletes the host/);
    const confirm = within(dialog).getByRole('button', { name: 'Decommission host' });
    const input = within(dialog).getByRole('textbox', { name: `Type ${HOSTNAME} to confirm` });
    expect(input).toHaveFocus();
    expect(confirm).toBeDisabled();

    await user.type(input, 'host-debian');
    expect(confirm).toBeDisabled();
    await user.type(input, '-legacy');
    expect(confirm).toBeEnabled();
    await user.click(confirm);

    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/hosts');
    });
    expect(deletes).toEqual([
      {
        url: expect.stringContaining(`/api/v1/hosts/${HOST_ID}`) as string,
        authorization: `Bearer ${mockAccessToken(['admin', 'viewer'])}`,
      },
    ]);
    expect(await screen.findByText(`${HOSTNAME} decommissioned`)).toBeInTheDocument();
    expect(await screen.findByRole('link', { name: 'host-ubuntu-hardened' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: HOSTNAME })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Dismiss' }));
    expect(screen.queryByText(`${HOSTNAME} decommissioned`)).not.toBeInTheDocument();
  });

  it('does not offer decommissioning to a viewer', async () => {
    serveFreshFleet();
    renderRoute(`/hosts/${HOST_ID}`, { role: 'viewer' });

    expect(await screen.findByRole('heading', { name: HOSTNAME })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Decommission host' })).not.toBeInTheDocument();
  });

  it('is refused by the mock API for a viewer token, as the real API would', async () => {
    serveFreshFleet();

    const response = await fetch(`/api/v1/hosts/${HOST_ID}`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${mockAccessToken(['viewer'])}` },
    });

    expect(response.status).toBe(403);
    expect(response.headers.get('Content-Type')).toBe('application/problem+json');
    const anonymous = await fetch(`/api/v1/hosts/${HOST_ID}`, { method: 'DELETE' });
    expect(anonymous.status).toBe(401);
    expect(anonymous.headers.get('WWW-Authenticate')).toBe('Bearer');
  });

  it('keeps the dialog open and explains a 403', async () => {
    serveFreshFleet();
    server.use(
      http.delete('/api/v1/hosts/:hostId', () =>
        problemResponse({ type: 'about:blank', title: 'Forbidden', status: 403 }),
      ),
    );
    const { user, router } = renderRoute(`/hosts/${HOST_ID}`, { role: 'admin' });

    await user.click(await screen.findByRole('button', { name: 'Decommission host' }));
    const dialog = screen.getByRole('dialog');
    await user.type(within(dialog).getByRole('textbox'), HOSTNAME);
    await user.click(within(dialog).getByRole('button', { name: 'Decommission host' }));

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(
      'Insufficient permissionsYour account is not allowed to decommission hosts.',
    );
    expect(router.state.location.pathname).toBe(`/hosts/${HOST_ID}`);

    await user.click(within(dialog).getByRole('button', { name: 'Cancel' }));
    expect(dialog).not.toBeVisible();
  });

  it('treats a host that is already gone as decommissioned', async () => {
    serveFreshFleet();
    server.use(
      http.delete('/api/v1/hosts/:hostId', () =>
        problemResponse({ type: 'about:blank', title: 'Not Found', status: 404 }),
      ),
    );
    const { user, router } = renderRoute(`/hosts/${HOST_ID}`, { role: 'admin' });

    await user.click(await screen.findByRole('button', { name: 'Decommission host' }));
    await user.type(screen.getByRole('textbox'), HOSTNAME);
    await user.keyboard('{Enter}');

    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/hosts');
    });
    expect(await screen.findByText('It had already been removed.')).toBeInTheDocument();
  });
});
