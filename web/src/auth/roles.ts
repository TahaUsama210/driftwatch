import type { AuthUser, Role } from './types';

const KNOWN_ROLES: readonly Role[] = ['viewer', 'admin'];

/**
 * Whether the user holds `role`. Keycloak expands the composite `admin` role into the
 * token, but the rule is mirrored here so the UI never depends on that detail.
 *
 * These checks only decide what the UI offers. The API checks the signed token on every
 * request and is the enforcement point.
 */
export function hasRole(user: AuthUser, role: Role): boolean {
  if (user.roles.includes(role)) return true;
  return role === 'viewer' && user.roles.includes('admin');
}

/** The role to show next to the user's name. */
export function roleLabel(user: AuthUser): string {
  if (hasRole(user, 'admin')) return 'Admin';
  if (hasRole(user, 'viewer')) return 'Viewer';
  return 'No access';
}

/**
 * Realm roles from an access token's `realm_access.roles` claim, limited to the ones
 * the dashboard knows. The payload is decoded, not verified: signature checks are the
 * API's job, and a forged token here would only change which buttons are shown.
 */
export function rolesFromAccessToken(token: string): Role[] {
  const claims = decodeJwtPayload(token);
  const realmAccess = claims?.realm_access;
  if (typeof realmAccess !== 'object' || realmAccess === null) return [];
  const roles = (realmAccess as { roles?: unknown }).roles;
  if (!Array.isArray(roles)) return [];
  return KNOWN_ROLES.filter((role) => roles.includes(role));
}

function decodeJwtPayload(token: string): Record<string, unknown> | undefined {
  const payload = token.split('.')[1];
  if (!payload) return undefined;
  try {
    const base64 = payload.replace(/-/g, '+').replace(/_/g, '/');
    const bytes = Uint8Array.from(atob(base64), (char) => char.charCodeAt(0));
    const parsed: unknown = JSON.parse(new TextDecoder().decode(bytes));
    return typeof parsed === 'object' && parsed !== null
      ? (parsed as Record<string, unknown>)
      : undefined;
  } catch {
    return undefined;
  }
}
