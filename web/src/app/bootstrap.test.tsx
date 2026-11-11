import { act, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { MockAuth } from '../auth/mockAuth';
import { server } from '../test/server';
import { loadOidcAuth, startApp } from './bootstrap';

let root: Root | undefined;

function mount(): Root {
  const container = document.createElement('div');
  document.body.append(container);
  root = createRoot(container);
  return root;
}

afterEach(() => {
  act(() => {
    root?.unmount();
  });
  root = undefined;
});

describe('startApp', () => {
  it('explains that it cannot start when /config.json fails to load', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    server.use(http.get('/config.json', () => new HttpResponse(null, { status: 500 })));

    await act(() => startApp(mount(), loadOidcAuth));

    expect(screen.getByRole('heading', { name: 'Driftwatch could not start' })).toBeInTheDocument();
    expect(screen.getByText('/config.json returned HTTP 500.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });

  it('builds an OIDC controller from a valid /config.json', async () => {
    server.use(
      http.get('/config.json', () =>
        HttpResponse.json({
          oidc: { authority: 'http://localhost:8400/auth/realms/driftwatch', clientId: 'web' },
        }),
      ),
    );

    const auth = await loadOidcAuth();

    expect(auth.kind).toBe('oidc');
    expect(auth.getState()).toEqual({ status: 'loading' });
  });

  it('renders the dashboard once the controller has resolved the session', async () => {
    const auth = new MockAuth('admin');
    const init = vi.spyOn(auth, 'init');

    await act(() => startApp(mount(), () => Promise.resolve(auth)));

    expect(init).toHaveBeenCalledTimes(1);
    expect(await screen.findByRole('navigation', { name: 'Primary' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Demo Admin/ })).toBeInTheDocument();
  });
});
