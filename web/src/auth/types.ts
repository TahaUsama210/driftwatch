/**
 * The app's view of authentication, independent of how it is implemented: OIDC against
 * Keycloak in real deployments (`OidcAuth`), a fake signed-in user in mock mode and
 * tests (`MockAuth`).
 */

/** Realm roles the dashboard knows about. `admin` is a composite that includes `viewer`. */
export type Role = 'viewer' | 'admin';

export interface AuthUser {
  /** Stable subject (`sub`); used to key per-user state such as the query cache. */
  id: string;
  username: string;
  displayName: string;
  roles: readonly Role[];
}

/** Something to tell the person on the signed-out screen (why they are there). */
export interface AuthNotice {
  title: string;
  detail?: string;
}

export type AuthState =
  | { status: 'loading' }
  | { status: 'signed-out'; notice?: AuthNotice }
  | { status: 'signed-in'; user: AuthUser };

/** What the API client needs: a token per request and a way out when it is rejected. */
export interface ApiAuth {
  /** A current access token, renewing first if it has expired; `undefined` when signed out. */
  getAccessToken(): Promise<string | undefined>;
  /** Fetches a fresh token (deduplicated across concurrent callers); `undefined` on failure. */
  renewAccessToken(): Promise<string | undefined>;
  /** The API rejected even a fresh token: send the person to sign in again (loop-guarded). */
  reauthenticate(): void;
}

export interface AuthController extends ApiAuth {
  readonly kind: 'oidc' | 'mock';
  /** Bound, so it can be handed to useSyncExternalStore directly. */
  getState: () => AuthState;
  subscribe: (listener: () => void) => () => void;
  /** Resolves the initial state (callback handling, session restore). Called once at startup. */
  init(): Promise<void>;
  /** Starts an interactive sign-in. Only ever called from a user action. */
  signIn(): Promise<void>;
  signOut(): Promise<void>;
  /** Mock mode only: change the fake user's role for demos. */
  switchRole?: (role: MockRole) => void;
}

/** Roles the mock user can take; `none` demonstrates a signed-in account without access. */
export type MockRole = 'admin' | 'viewer' | 'none';
