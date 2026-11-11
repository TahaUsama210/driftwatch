import {
  ErrorResponse,
  InMemoryWebStorage,
  UserManager,
  WebStorageStateStore,
  type User,
  type UserManagerSettings,
} from 'oidc-client-ts';
import { rolesFromAccessToken } from './roles';
import type { RuntimeConfig } from './runtimeConfig';
import type { AuthController, AuthNotice, AuthState, AuthUser } from './types';

/** Where Keycloak sends the browser back with the authorization code. */
export const SIGNIN_CALLBACK_PATH = '/signin-callback';
/** Loaded in a hidden iframe for silent sign-in; a tiny page, not the SPA (see silentRenew.ts). */
export const SILENT_RENEW_PATH = '/silent-renew.html';

/** If the API rejects a fresh session again within this window, stop redirecting. */
export const REAUTH_WINDOW_MS = 60_000;
const REAUTH_MARKER_KEY = 'driftwatch:reauth-at';

/** Errors that only mean "no single sign-on session"; the signed-out screen says nothing. */
const NO_SESSION_ERRORS = new Set([
  'login_required',
  'interaction_required',
  'consent_required',
  'account_selection_required',
]);

/**
 * Authorization Code + PKCE (S256, oidc-client-ts's default for the code flow).
 *
 * Token storage: access, refresh and ID tokens live in this tab's memory only, never in
 * localStorage or sessionStorage, so a script injected into the page finds nothing at
 * rest to exfiltrate (in particular no refresh token). A reload restores the session
 * with a silent `prompt=none` request in a hidden iframe, which works because Keycloak
 * is served from the same origin (no third-party cookies involved).
 *
 * Only the PKCE verifier and `state` must survive the redirect to Keycloak and back.
 * They go to sessionStorage (per tab, gone when it closes, removed after the callback)
 * instead of the library's default, localStorage.
 */
export function oidcSettings(config: RuntimeConfig, origin: string): UserManagerSettings {
  return {
    authority: config.oidc.authority,
    client_id: config.oidc.clientId,
    redirect_uri: `${origin}${SIGNIN_CALLBACK_PATH}`,
    silent_redirect_uri: `${origin}${SILENT_RENEW_PATH}`,
    post_logout_redirect_uri: `${origin}/`,
    response_type: 'code',
    scope: 'openid profile',
    userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
    stateStore: new WebStorageStateStore({
      store: window.sessionStorage,
      prefix: 'driftwatch.oidc.',
    }),
    // Renews a minute before expiry with the refresh token (falls back to the iframe).
    automaticSilentRenew: true,
    silentRequestTimeoutInSeconds: 10,
    monitorSession: false,
    loadUserInfo: false,
  };
}

/** The part of `UserManager` this module uses, so tests can substitute a fake. */
export interface OidcUserManager {
  signinRedirect(args: { state: SignInState }): Promise<void>;
  signinRedirectCallback(url: string): Promise<User>;
  signinSilent(): Promise<User | null>;
  signoutRedirect(): Promise<void>;
  removeUser(): Promise<void>;
  readonly events: {
    addUserLoaded(callback: (user: User) => void): unknown;
    addUserUnloaded(callback: () => void): unknown;
    addSilentRenewError(callback: (error: Error) => void): unknown;
  };
}

/** Carried through Keycloak in the OIDC `state`, so sign-in returns to the same page. */
interface SignInState {
  returnTo: string;
}

export function createOidcAuth(config: RuntimeConfig): OidcAuth {
  return new OidcAuth(new UserManager(oidcSettings(config, window.location.origin)));
}

export class OidcAuth implements AuthController {
  readonly kind = 'oidc';
  private readonly manager: OidcUserManager;
  private readonly storage: Pick<Storage, 'getItem' | 'setItem'>;
  private readonly now: () => number;
  private readonly listeners = new Set<() => void>();
  private state: AuthState = { status: 'loading' };
  private user: User | null = null;
  private renewal: Promise<string | undefined> | undefined;
  /** The last renewal failed without an answer from the identity provider (outage). */
  private renewalUnreachable = false;
  private leaving = false;

  constructor(
    manager: OidcUserManager,
    options: { storage?: Pick<Storage, 'getItem' | 'setItem'>; now?: () => number } = {},
  ) {
    this.manager = manager;
    this.storage = options.storage ?? window.sessionStorage;
    this.now = options.now ?? Date.now;
    // Background renewals report through events. During init() the callback or the
    // session restore sets the state itself, after fixing up the URL.
    manager.events.addUserLoaded((user) => {
      if (this.state.status !== 'loading') this.setUser(user);
    });
    manager.events.addUserUnloaded(() => {
      if (this.state.status !== 'loading' && !this.leaving) this.setSignedOut();
    });
    manager.events.addSilentRenewError((error) => {
      // The next API call notices the expired token and recovers (see client.ts).
      console.warn('Driftwatch: background token renewal failed.', error);
    });
  }

  getState = (): AuthState => this.state;

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  async init(): Promise<void> {
    if (window.location.pathname === SIGNIN_CALLBACK_PATH) {
      await this.completeSignIn();
    } else {
      await this.restoreSession();
    }
  }

  async signIn(): Promise<void> {
    try {
      await this.manager.signinRedirect({ state: { returnTo: currentLocation() } });
    } catch (error) {
      console.error('Driftwatch: could not start sign-in.', error);
      this.setSignedOut({
        title: 'The sign-in service is unreachable',
        detail: 'Driftwatch could not contact the identity provider. Try again in a moment.',
      });
    }
  }

  async signOut(): Promise<void> {
    this.leaving = true;
    try {
      // Ends the Keycloak session too, then Keycloak returns to post_logout_redirect_uri.
      await this.manager.signoutRedirect();
    } catch (error) {
      console.error('Driftwatch: could not reach the end-session endpoint.', error);
      await this.manager.removeUser();
      this.setSignedOut({
        title: 'Signed out of Driftwatch',
        detail:
          'The identity provider could not be reached, so its single sign-on session may still be active.',
      });
    } finally {
      this.leaving = false;
    }
  }

  async getAccessToken(): Promise<string | undefined> {
    const user = this.user;
    if (!user) return undefined;
    if (user.expired !== true) return user.access_token;
    return this.renewAccessToken();
  }

  renewAccessToken(): Promise<string | undefined> {
    this.renewal ??= this.manager
      .signinSilent()
      .then(
        (user) => {
          this.renewalUnreachable = false;
          if (user) this.setUser(user);
          return user?.access_token;
        },
        (error: unknown) => {
          // An OAuth error (invalid_grant, login_required) is Keycloak saying the session
          // is over. Anything else (network error, timeout, nginx's 502) means it did not
          // answer at all.
          this.renewalUnreachable = !(error instanceof ErrorResponse);
          console.warn('Driftwatch: token renewal failed.', error);
          return undefined;
        },
      )
      .finally(() => {
        this.renewal = undefined;
      });
    return this.renewal;
  }

  reauthenticate(): void {
    if (this.leaving) return;
    if (this.renewalUnreachable) {
      // Redirecting now would swap the dashboard for the proxy's error page, which never
      // recovers. Stay: the 401 reaches the UI, and a later request renews once the
      // identity provider is back (or, if the session ended meanwhile, redirects then).
      console.warn('Driftwatch: the identity provider is unreachable; not redirecting.');
      return;
    }
    const last = Number(this.readMarker());
    if (Number.isFinite(last) && this.now() - last < REAUTH_WINDOW_MS) {
      // Signed in moments ago and the API still says 401: redirecting again would loop.
      // The 401 reaches the UI instead, which offers an explicit "Sign in again".
      console.warn('Driftwatch: the API rejected a fresh session; not redirecting again.');
      return;
    }
    this.writeMarker(String(this.now()));
    this.leaving = true;
    void this.signIn().finally(() => {
      this.leaving = false;
    });
  }

  private async completeSignIn(): Promise<void> {
    try {
      const user = await this.manager.signinRedirectCallback(window.location.href);
      // Drop the one-time code from the address bar before the app reads the URL.
      window.history.replaceState(null, '', safeReturnTo(user.state));
      this.setUser(user);
    } catch (error) {
      console.error('Driftwatch: sign-in callback failed.', error);
      window.history.replaceState(null, '', '/');
      this.setSignedOut({
        title: 'Sign-in did not complete',
        detail:
          error instanceof ErrorResponse && error.error_description
            ? error.error_description
            : 'The response from the identity provider could not be verified. Please sign in again.',
      });
    }
  }

  private async restoreSession(): Promise<void> {
    try {
      const user = await this.manager.signinSilent();
      if (user) this.setUser(user);
      else this.setSignedOut();
    } catch (error) {
      if (error instanceof ErrorResponse && NO_SESSION_ERRORS.has(error.error ?? '')) {
        this.setSignedOut();
        return;
      }
      console.warn('Driftwatch: could not restore the session.', error);
      this.setSignedOut({
        title: 'Your session could not be restored',
        detail: 'The identity provider did not answer. Sign in to continue.',
      });
    }
  }

  private setUser(user: User): void {
    this.user = user;
    const next = toAuthUser(user);
    // Token renewals replace the User every few minutes; only re-render on real changes.
    if (this.state.status === 'signed-in' && sameUser(this.state.user, next)) return;
    this.setState({ status: 'signed-in', user: next });
  }

  private setSignedOut(notice?: AuthNotice): void {
    this.user = null;
    this.setState(notice ? { status: 'signed-out', notice } : { status: 'signed-out' });
  }

  private setState(next: AuthState): void {
    this.state = next;
    for (const listener of this.listeners) listener();
  }

  private readMarker(): string | null {
    try {
      return this.storage.getItem(REAUTH_MARKER_KEY);
    } catch {
      return null;
    }
  }

  private writeMarker(value: string): void {
    try {
      this.storage.setItem(REAUTH_MARKER_KEY, value);
    } catch {
      // Without storage the guard cannot persist across the redirect; still sign in.
    }
  }
}

function toAuthUser(user: User): AuthUser {
  const { sub, preferred_username: username, name } = user.profile;
  return {
    id: sub,
    username: username ?? sub,
    displayName: name ?? username ?? sub,
    roles: rolesFromAccessToken(user.access_token),
  };
}

function sameUser(a: AuthUser, b: AuthUser): boolean {
  return (
    a.id === b.id &&
    a.username === b.username &&
    a.displayName === b.displayName &&
    a.roles.length === b.roles.length &&
    a.roles.every((role) => b.roles.includes(role))
  );
}

function currentLocation(): string {
  const { pathname, search, hash } = window.location;
  return pathname === SIGNIN_CALLBACK_PATH ? '/' : `${pathname}${search}${hash}`;
}

/** Only same-origin app paths are accepted, so `state` cannot become an open redirect. */
export function safeReturnTo(state: unknown): string {
  const returnTo = (state as Partial<SignInState> | null | undefined)?.returnTo;
  if (typeof returnTo !== 'string' || !returnTo.startsWith('/')) return '/';
  const url = new URL(returnTo, window.location.origin);
  if (url.origin !== window.location.origin || url.pathname === SIGNIN_CALLBACK_PATH) return '/';
  return `${url.pathname}${url.search}${url.hash}`;
}
