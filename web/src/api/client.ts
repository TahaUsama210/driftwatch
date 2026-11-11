import createClient from 'openapi-fetch';
import type { ApiAuth } from '../auth/types';
import type { paths } from './schema';
import type { Problem } from './types';

let apiAuth: ApiAuth | undefined;

/** Registers where API calls get their access token. Called once at startup (and by tests). */
export function configureApiAuth(auth: ApiAuth | undefined): void {
  apiAuth = auth;
}

function withBearer(request: Request, token: string | undefined): Request {
  const authed = request.clone();
  // The token is only ever sent to this origin, whatever URL a caller constructs.
  if (token && new URL(request.url).origin === globalThis.location.origin) {
    authed.headers.set('Authorization', `Bearer ${token}`);
  }
  return authed;
}

/**
 * Sends `request` with the current access token. On 401 (token revoked, Keycloak
 * restarted, clock skew) it renews once and replays the request; if the API still
 * refuses, it asks for an interactive re-authentication and returns the 401.
 *
 * `fetch` is looked up per request rather than captured once, so anything that wraps
 * the global after this module loads (MSW in tests, instrumentation) is honoured.
 */
async function authorizedFetch(request: Request): Promise<Response> {
  const auth = apiAuth;
  if (!auth) return globalThis.fetch(request);

  const response = await globalThis.fetch(withBearer(request, await auth.getAccessToken()));
  if (response.status !== 401) return response;

  const renewed = await auth.renewAccessToken();
  if (renewed !== undefined) {
    const retried = await globalThis.fetch(withBearer(request, renewed));
    if (retried.status !== 401) return retried;
  }
  auth.reauthenticate();
  return response;
}

/**
 * Typed client for the Driftwatch API. Requests are same-origin: the Vite dev server
 * and the production nginx both proxy `/api` to the API, so no CORS is involved.
 */
export const api = createClient<paths>({
  baseUrl: globalThis.location.origin,
  fetch: authorizedFetch,
});

type ErrorKind = 'http' | 'network' | 'invalid-response';

/** An API failure carrying RFC 9457 problem details, whether the server sent them or not. */
export class ApiError extends Error {
  override readonly name = 'ApiError';
  readonly kind: ErrorKind;
  readonly problem: Problem;

  constructor(kind: ErrorKind, problem: Problem, options?: ErrorOptions) {
    super(problem.detail ?? problem.title, options);
    this.kind = kind;
    this.problem = problem;
  }

  /** HTTP status, or `undefined` when no response arrived. */
  get status(): number | undefined {
    return this.kind === 'http' ? this.problem.status : undefined;
  }

  static fromResponse(response: Response, body: unknown): ApiError {
    const problem = parseProblem(body) ?? {
      type: 'about:blank',
      title: response.statusText || `HTTP ${response.status}`,
      status: response.status,
      detail: 'The API returned an error without problem details.',
    };
    return new ApiError('http', { ...problem, status: response.status });
  }

  static network(cause: unknown): ApiError {
    return new ApiError(
      'network',
      {
        type: 'about:blank',
        title: 'Cannot reach the Driftwatch API',
        status: 0,
        detail: 'The request did not complete. Check that the API is running, then retry.',
      },
      { cause },
    );
  }

  static invalidResponse(cause: unknown): ApiError {
    return new ApiError(
      'invalid-response',
      {
        type: 'about:blank',
        title: 'Unexpected response from the API',
        status: 0,
        detail: 'The response body could not be read as JSON.',
      },
      { cause },
    );
  }
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError;
}

export function parseProblem(body: unknown): Problem | undefined {
  if (typeof body !== 'object' || body === null) return undefined;
  const candidate = body as Partial<Record<keyof Problem, unknown>>;
  if (typeof candidate.title !== 'string' || typeof candidate.status !== 'number') return undefined;

  const problem: Problem = {
    type: typeof candidate.type === 'string' ? candidate.type : 'about:blank',
    title: candidate.title,
    status: candidate.status,
  };
  if (typeof candidate.detail === 'string') problem.detail = candidate.detail;
  if (typeof candidate.instance === 'string') problem.instance = candidate.instance;
  if (Array.isArray(candidate.errors)) {
    problem.errors = candidate.errors.filter(
      (item): item is { field: string; message: string } =>
        typeof item === 'object' &&
        item !== null &&
        typeof (item as { field?: unknown }).field === 'string' &&
        typeof (item as { message?: unknown }).message === 'string',
    );
  }
  return problem;
}

function isAbort(error: unknown): boolean {
  return error instanceof Error && error.name === 'AbortError';
}

interface ClientResult<T> {
  data?: T;
  error?: unknown;
  response: Response;
}

/**
 * Resolves an openapi-fetch call to its data or throws an {@link ApiError}. Aborts are
 * rethrown untouched so TanStack Query can recognise cancelled requests.
 */
export async function unwrap<T>(call: Promise<ClientResult<T>>): Promise<T> {
  let result: ClientResult<T>;
  try {
    result = await call;
  } catch (error) {
    if (isAbort(error)) throw error;
    if (error instanceof SyntaxError) throw ApiError.invalidResponse(error);
    throw ApiError.network(error);
  }

  const { data, error, response } = result;
  if (!response.ok) throw ApiError.fromResponse(response, error);
  if (data === undefined) throw ApiError.invalidResponse(new Error('Empty response body'));
  return data;
}

/** Like {@link unwrap}, for endpoints that answer 204 No Content. */
export async function unwrapEmpty(call: Promise<ClientResult<unknown>>): Promise<void> {
  let result: ClientResult<unknown>;
  try {
    result = await call;
  } catch (error) {
    if (isAbort(error)) throw error;
    throw ApiError.network(error);
  }
  if (!result.response.ok) throw ApiError.fromResponse(result.response, result.error);
}

/** Problem details for any thrown value, for display in error states. */
export function toProblem(error: unknown): Problem {
  if (isApiError(error)) return error.problem;
  return {
    type: 'about:blank',
    title: 'Something went wrong',
    status: 0,
    detail: error instanceof Error ? error.message : 'An unexpected error occurred.',
  };
}
