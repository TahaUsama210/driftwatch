import { mockAccessToken } from './mockToken';
import type { AuthController, AuthState, AuthUser, MockRole } from './types';

/** The demo accounts, mirroring the realm's `admin` and `viewer` users. */
const MOCK_USERS: Record<MockRole, AuthUser> = {
  admin: {
    id: 'mock-admin',
    username: 'admin',
    displayName: 'Demo Admin',
    // Keycloak expands the composite admin role into the token the same way.
    roles: ['admin', 'viewer'],
  },
  viewer: { id: 'mock-viewer', username: 'viewer', displayName: 'Demo Viewer', roles: ['viewer'] },
  none: { id: 'mock-none', username: 'guest', displayName: 'Demo Guest', roles: [] },
};

const MOCK_ROLES: readonly MockRole[] = ['admin', 'viewer', 'none'];

/** `?role=admin|viewer|none` on the first URL picks the mock user; admin by default. */
export function mockRoleFromSearch(search: string): MockRole {
  const requested = new URLSearchParams(search).get('role');
  return MOCK_ROLES.find((role) => role === requested) ?? 'admin';
}

/**
 * Authentication for `npm run dev:mock` and tests: no identity provider, a fake user
 * whose role can be switched, and tokens the MSW handlers understand.
 */
export class MockAuth implements AuthController {
  readonly kind = 'mock';
  private readonly listeners = new Set<() => void>();
  private role: MockRole;
  private state: AuthState;

  constructor(role: MockRole = 'admin', { signedIn = true }: { signedIn?: boolean } = {}) {
    this.role = role;
    this.state = signedIn ? this.signedInState() : { status: 'signed-out' };
  }

  getState = (): AuthState => this.state;

  subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  init(): Promise<void> {
    return Promise.resolve();
  }

  signIn(): Promise<void> {
    this.setState(this.signedInState());
    return Promise.resolve();
  }

  signOut(): Promise<void> {
    this.setState({ status: 'signed-out' });
    return Promise.resolve();
  }

  getAccessToken(): Promise<string | undefined> {
    return Promise.resolve(
      this.state.status === 'signed-in' ? mockAccessToken(this.state.user.roles) : undefined,
    );
  }

  renewAccessToken(): Promise<string | undefined> {
    return this.getAccessToken();
  }

  reauthenticate(): void {
    // Mock tokens never expire, so a 401 means the request had no token at all.
    console.warn('Driftwatch: the mock API rejected the request as unauthenticated.');
  }

  switchRole = (role: MockRole): void => {
    this.role = role;
    if (this.state.status === 'signed-in') this.setState(this.signedInState());
  };

  private signedInState(): AuthState {
    return { status: 'signed-in', user: MOCK_USERS[this.role] };
  }

  private setState(next: AuthState): void {
    this.state = next;
    for (const listener of this.listeners) listener();
  }
}
