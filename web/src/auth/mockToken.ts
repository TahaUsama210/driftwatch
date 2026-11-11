import type { Role } from './types';

/**
 * Mock mode's stand-in for a JWT: `mock.<roles>`. MockAuth sends it as the bearer token
 * and the MSW handlers read the roles back, so the mock API enforces the same
 * authorization matrix as the real one (401 without a token, 403 without the role).
 */
const PREFIX = 'mock.';

export function mockAccessToken(roles: readonly Role[]): string {
  return `${PREFIX}${roles.length > 0 ? roles.join('+') : 'none'}`;
}

/** The roles in a mock token, or `undefined` if `token` is not one. */
export function rolesFromMockToken(token: string): Role[] | undefined {
  if (!token.startsWith(PREFIX)) return undefined;
  const parts = token.slice(PREFIX.length).split('+');
  if (parts.length === 1 && parts[0] === 'none') return [];
  const roles = parts.filter((part): part is Role => part === 'viewer' || part === 'admin');
  return roles.length === parts.length ? roles : undefined;
}
