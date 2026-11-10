import { ErrorResponse, InMemoryWebStorage, User, WebStorageStateStore } from 'oidc-client-ts';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  OidcAuth,
  oidcSettings,
  REAUTH_WINDOW_MS,
  safeReturnTo,
  SIGNIN_CALLBACK_PATH,
  type OidcUserManager,
} from './oidcAuth';

/** A JWT-shaped string; only the payload matters to the client. */
function accessToken(roles: string[]): string {
  const payload = btoa(JSON.stringify({ realm_access: { roles } }))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '');
  return `eyJhbGciOiJub25lIn0.${payload}.signature`;
}

function buildUser({
  roles = ['viewer'],
  expiresIn = 300,
  state,
}: { roles?: string[]; expiresIn?: number; state?: unknown } = {}): User {
  return new User({
    access_token: accessToken(roles),
    token_type: 'Bearer',
    profile: {
      sub: 'user-1',
      iss: 'http://localhost:8400/auth/realms/driftwatch',
      aud: 'driftwatch-web',
      exp: 0,
      iat: 0,
      preferred_username: 'viewer',
      name: 'Demo Viewer',
    },
    expires_at: Math.floor(Date.now() / 1000) + expiresIn,
    userState: state,
  });
}

function fakeManager() {
  const listeners = {
    loaded: [] as ((user: User) => void)[],
    unloaded: [] as (() => void)[],
  };
  const manager = {
    signinRedirect: vi.fn<OidcUserManager['signinRedirect']>().mockResolvedValue(undefined),
    signinRedirectCallback: vi.fn<OidcUserManager['signinRedirectCallback']>(),
    signinSilent: vi.fn<OidcUserManager['signinSilent']>(),
    signoutRedirect: vi.fn<OidcUserManager['signoutRedirect']>().mockResolvedValue(undefined),
    removeUser: vi.fn<OidcUserManager['removeUser']>().mockImplementation(() => {
      for (const listener of listeners.unloaded) listener();
      return Promise.resolve();
    }),
    events: {
      addUserLoaded: (callback: (user: User) => void) => listeners.loaded.push(callback),
      addUserUnloaded: (callback: () => void) => listeners.unloaded.push(callback),
      addSilentRenewError: () => undefined,
    },
  };
  const emitUserLoaded = (user: User) => {
    for (const listener of listeners.loaded) listener(user);
  };
  return { manager, emitUserLoaded };
}

let now = Date.parse('2026-11-09T12:00:00Z');
const clock = () => now;

function setup() {
  const fake = fakeManager();
  const auth = new OidcAuth(fake.manager, { storage: window.sessionStorage, now: clock });
  return { auth, ...fake };
}

beforeEach(() => {
  vi.spyOn(console, 'warn').mockImplementation(() => undefined);
  vi.spyOn(console, 'error').mockImplementation(() => undefined);
  window.history.replaceState(null, '', '/');
});

afterEach(() => {
  now = Date.parse('2026-11-09T12:00:00Z');
});

describe('oidcSettings', () => {
  it('keeps tokens in memory and only the PKCE state in sessionStorage', () => {
    const settings = oidcSettings(
      { oidc: { authority: 'http://localhost:8400/auth/realms/driftwatch', clientId: 'web' } },
      'http://localhost:8400',
    );

    expect(settings).toMatchObject({
      client_id: 'web',
      response_type: 'code',
      redirect_uri: `http://localhost:8400${SIGNIN_CALLBACK_PATH}`,
      silent_redirect_uri: 'http://localhost:8400/silent-renew.html',
      post_logout_redirect_uri: 'http://localhost:8400/',
      automaticSilentRenew: true,
    });
    expect(settings.disablePKCE).toBeUndefined();
    expect(settings.userStore).toBeInstanceOf(WebStorageStateStore);
    expect((settings.userStore as unknown as { _store: Storage })._store).toBeInstanceOf(
      InMemoryWebStorage,
    );
    expect((settings.stateStore as unknown as { _store: Storage })._store).toBe(
      window.sessionStorage,
    );
  });
});

describe('OidcAuth.init', () => {
  it('restores an existing Keycloak session silently, with roles from the access token', async () => {
    const { auth, manager } = setup();
    manager.signinSilent.mockResolvedValue(buildUser({ roles: ['admin', 'viewer', 'other'] }));

    await auth.init();

    expect(auth.getState()).toEqual({
      status: 'signed-in',
      user: {
        id: 'user-1',
        username: 'viewer',
        displayName: 'Demo Viewer',
        roles: ['viewer', 'admin'],
      },
    });
    expect(manager.signinRedirect).not.toHaveBeenCalled();
  });

  it('lands signed out, without a notice and without redirecting, when there is no session', async () => {
    const { auth, manager } = setup();
    manager.signinSilent.mockRejectedValue(new ErrorResponse({ error: 'login_required' }));

    await auth.init();

    expect(auth.getState()).toEqual({ status: 'signed-out' });
    expect(manager.signinRedirect).not.toHaveBeenCalled();
  });

  it('explains when the identity provider does not answer', async () => {
    const { auth, manager } = setup();
    manager.signinSilent.mockRejectedValue(new Error('Frame window timed out'));

    await auth.init();

    expect(auth.getState()).toMatchObject({
      status: 'signed-out',
      notice: { title: 'Your session could not be restored' },
    });
  });

  it('completes the redirect callback and returns to the page sign-in started from', async () => {
    window.history.replaceState(null, '', `${SIGNIN_CALLBACK_PATH}?code=abc&state=xyz`);
    const { auth, manager } = setup();
    manager.signinRedirectCallback.mockResolvedValue(
      buildUser({ state: { returnTo: '/hosts?status=STALE#top' } }),
    );

    await auth.init();

    expect(manager.signinRedirectCallback).toHaveBeenCalledWith(
      expect.stringContaining('code=abc'),
    );
    expect(window.location.pathname + window.location.search + window.location.hash).toBe(
      '/hosts?status=STALE#top',
    );
    expect(auth.getState().status).toBe('signed-in');
    expect(manager.signinSilent).not.toHaveBeenCalled();
  });

  it('shows the identity provider error and strips the callback URL when sign-in fails', async () => {
    window.history.replaceState(null, '', `${SIGNIN_CALLBACK_PATH}?error=access_denied&state=x`);
    const { auth, manager } = setup();
    manager.signinRedirectCallback.mockRejectedValue(
      new ErrorResponse({ error: 'access_denied', error_description: 'User cancelled' }),
    );

    await auth.init();

    expect(window.location.pathname).toBe('/');
    expect(auth.getState()).toEqual({
      status: 'signed-out',
      notice: { title: 'Sign-in did not complete', detail: 'User cancelled' },
    });
  });
});

describe('safeReturnTo', () => {
  it.each([
    [{ returnTo: '/checks/ssh.permit_root_login' }, '/checks/ssh.permit_root_login'],
    [{ returnTo: 'https://evil.example/' }, '/'],
    [{ returnTo: '//evil.example/path' }, '/'],
    [{ returnTo: `${SIGNIN_CALLBACK_PATH}?code=1` }, '/'],
    [undefined, '/'],
  ])('maps %j to %s', (state, expected) => {
    expect(safeReturnTo(state)).toBe(expected);
  });
});

describe('OidcAuth sessions', () => {
  async function signedIn(user = buildUser()) {
    const context = setup();
    context.manager.signinSilent.mockResolvedValue(user);
    await context.auth.init();
    context.manager.signinSilent.mockReset();
    return context;
  }

  it('starts sign-in only when asked, carrying the current location', async () => {
    window.history.replaceState(null, '', '/events?direction=REGRESSED');
    const { auth, manager } = setup();

    await auth.signIn();

    expect(manager.signinRedirect).toHaveBeenCalledWith({
      state: { returnTo: '/events?direction=REGRESSED' },
    });
  });

  it('stays on the landing with a notice when the sign-in service is unreachable', async () => {
    const { auth, manager } = setup();
    manager.signinRedirect.mockRejectedValue(new TypeError('Failed to fetch'));

    await auth.signIn();

    expect(auth.getState()).toMatchObject({
      status: 'signed-out',
      notice: { title: 'The sign-in service is unreachable' },
    });
  });

  it('returns the current token, and renews an expired one first', async () => {
    const { auth, manager } = await signedIn(buildUser({ expiresIn: -10 }));
    const fresh = buildUser({ roles: ['admin'] });
    manager.signinSilent.mockResolvedValue(fresh);

    await expect(auth.getAccessToken()).resolves.toBe(fresh.access_token);
    await expect(auth.getAccessToken()).resolves.toBe(fresh.access_token);
    expect(manager.signinSilent).toHaveBeenCalledTimes(1);
    expect(auth.getState()).toMatchObject({ user: { roles: ['admin'] } });
  });

  it('shares one renewal between concurrent callers and reports failure as undefined', async () => {
    const { auth, manager } = await signedIn();
    manager.signinSilent.mockRejectedValue(new Error('refresh token expired'));

    const results = await Promise.all([auth.renewAccessToken(), auth.renewAccessToken()]);

    expect(results).toEqual([undefined, undefined]);
    expect(manager.signinSilent).toHaveBeenCalledTimes(1);
  });

  it('keeps the same state object when a background renewal changes nothing visible', async () => {
    const { auth, emitUserLoaded } = await signedIn();
    const before = auth.getState();

    emitUserLoaded(buildUser());
    expect(auth.getState()).toBe(before);

    emitUserLoaded(buildUser({ roles: ['admin'] }));
    expect(auth.getState()).not.toBe(before);
  });

  it('re-authenticates on demand, but not again within the loop window', async () => {
    const { auth, manager } = await signedIn();

    auth.reauthenticate();
    auth.reauthenticate();
    await vi.waitFor(() => {
      expect(manager.signinRedirect).toHaveBeenCalledTimes(1);
    });

    // Let the first redirect attempt settle (it is still "leaving" until then).
    await new Promise((resolve) => setTimeout(resolve, 0));

    now += REAUTH_WINDOW_MS / 2;
    auth.reauthenticate();
    expect(manager.signinRedirect).toHaveBeenCalledTimes(1);

    now += REAUTH_WINDOW_MS;
    auth.reauthenticate();
    await vi.waitFor(() => {
      expect(manager.signinRedirect).toHaveBeenCalledTimes(2);
    });
  });

  it('does not leave the dashboard for sign-in while the identity provider is unreachable', async () => {
    const { auth, manager } = await signedIn(buildUser({ expiresIn: -10 }));
    // What oidc-client-ts throws when nginx answers 502 problem+json for the token endpoint.
    manager.signinSilent.mockRejectedValue(new Error('Invalid response Content-Type'));

    await expect(auth.renewAccessToken()).resolves.toBeUndefined();
    auth.reauthenticate();
    expect(manager.signinRedirect).not.toHaveBeenCalled();

    // Keycloak is back but the session ended meanwhile: now sign-in is the way out.
    manager.signinSilent.mockRejectedValue(new ErrorResponse({ error: 'invalid_grant' }));
    await expect(auth.renewAccessToken()).resolves.toBeUndefined();
    auth.reauthenticate();
    await vi.waitFor(() => {
      expect(manager.signinRedirect).toHaveBeenCalledTimes(1);
    });
  });

  it('signs out through the end-session endpoint', async () => {
    const { auth, manager } = await signedIn();

    await auth.signOut();

    expect(manager.signoutRedirect).toHaveBeenCalledTimes(1);
  });

  it('still forgets the tokens locally when the end-session endpoint is unreachable', async () => {
    const { auth, manager } = await signedIn();
    manager.signoutRedirect.mockRejectedValue(new TypeError('Failed to fetch'));

    await auth.signOut();

    expect(manager.removeUser).toHaveBeenCalled();
    expect(auth.getState()).toMatchObject({
      status: 'signed-out',
      notice: { title: 'Signed out of Driftwatch' },
    });
    await expect(auth.getAccessToken()).resolves.toBeUndefined();
  });
});
