/**
 * Deployment settings the bundle cannot know at build time. nginx renders
 * `/config.json` from env at container start (see nginx/templates); the Vite dev server
 * serves an equivalent for `npm run dev`. One image therefore runs in compose, k3d or
 * anywhere else without a rebuild.
 */
export interface RuntimeConfig {
  oidc: {
    /** Issuer URL, e.g. `http://localhost:8400/auth/realms/driftwatch`. */
    authority: string;
    clientId: string;
  };
}

export const RUNTIME_CONFIG_PATH = '/config.json';

export class RuntimeConfigError extends Error {
  override readonly name = 'RuntimeConfigError';
}

export async function loadRuntimeConfig(signal?: AbortSignal): Promise<RuntimeConfig> {
  let response: Response;
  try {
    response = await fetch(RUNTIME_CONFIG_PATH, {
      cache: 'no-store',
      headers: { Accept: 'application/json' },
      signal,
    });
  } catch (error) {
    throw new RuntimeConfigError(`${RUNTIME_CONFIG_PATH} could not be fetched.`, {
      cause: error,
    });
  }
  if (!response.ok) {
    throw new RuntimeConfigError(`${RUNTIME_CONFIG_PATH} returned HTTP ${response.status}.`);
  }

  let body: unknown;
  try {
    body = await response.json();
  } catch (error) {
    throw new RuntimeConfigError(`${RUNTIME_CONFIG_PATH} is not valid JSON.`, { cause: error });
  }
  return parseRuntimeConfig(body);
}

export function parseRuntimeConfig(body: unknown): RuntimeConfig {
  const oidc = (body as { oidc?: unknown } | null)?.oidc;
  if (typeof oidc !== 'object' || oidc === null) {
    throw new RuntimeConfigError(`${RUNTIME_CONFIG_PATH} has no "oidc" object.`);
  }
  const { authority, clientId } = oidc as { authority?: unknown; clientId?: unknown };
  if (typeof authority !== 'string' || !isHttpUrl(authority)) {
    throw new RuntimeConfigError(
      `${RUNTIME_CONFIG_PATH}: "oidc.authority" must be an http(s) URL.`,
    );
  }
  if (typeof clientId !== 'string' || clientId.trim() === '') {
    throw new RuntimeConfigError(
      `${RUNTIME_CONFIG_PATH}: "oidc.clientId" must be a non-empty string.`,
    );
  }
  return { oidc: { authority: authority.replace(/\/+$/, ''), clientId } };
}

function isHttpUrl(value: string): boolean {
  try {
    const url = new URL(value);
    return url.protocol === 'https:' || url.protocol === 'http:';
  } catch {
    return false;
  }
}
