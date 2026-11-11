import { http, HttpResponse } from 'msw';
import { describe, expect, it, vi } from 'vitest';
import { shouldRetry } from '../app/queryClient';
import type { ApiAuth } from '../auth/types';
import { problemResponse } from '../mocks/handlers';
import { buildHostPage } from '../test/factories';
import { server } from '../test/server';
import { api, ApiError, configureApiAuth, parseProblem, unwrap } from './client';

const HOST = '/api/v1/hosts/:hostId';
const hostId = '00000000-0000-4000-8000-000000000000';
const getHost = () => unwrap(api.GET('/api/v1/hosts/{hostId}', { params: { path: { hostId } } }));

describe('unwrap', () => {
  it('turns problem+json into an ApiError carrying the problem details', async () => {
    server.use(
      http.get(HOST, () =>
        problemResponse({
          type: 'about:blank',
          title: 'Not Found',
          status: 404,
          detail: `Host ${hostId} does not exist.`,
        }),
      ),
    );

    const error = await getHost().catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({
      kind: 'http',
      status: 404,
      problem: { title: 'Not Found', detail: `Host ${hostId} does not exist.` },
    });
  });

  it('describes an error response that has no problem body', async () => {
    server.use(http.get(HOST, () => new HttpResponse('<html>Bad Gateway</html>', { status: 502 })));

    const error = await getHost().catch((caught: unknown) => caught);

    expect(error).toMatchObject({ kind: 'http', status: 502 });
    expect((error as ApiError).problem.detail).toMatch(/without problem details/);
  });

  it('reports a network failure without an HTTP status', async () => {
    server.use(http.get(HOST, () => HttpResponse.error()));

    const error = await getHost().catch((caught: unknown) => caught);

    expect(error).toMatchObject({ kind: 'network', status: undefined });
    expect((error as ApiError).problem.title).toBe('Cannot reach the Driftwatch API');
  });
});

describe('parseProblem', () => {
  it('keeps well-formed field errors and drops malformed ones', () => {
    const problem = parseProblem({
      title: 'Bad Request',
      status: 400,
      errors: [{ field: 'hours', message: 'must be at most 720' }, { field: 3 }, null],
    });
    expect(problem?.errors).toEqual([{ field: 'hours', message: 'must be at most 720' }]);
  });

  it.each([null, 'oops', { title: 'missing status' }, { status: 500 }])(
    'rejects %j as a problem',
    (body) => {
      expect(parseProblem(body)).toBeUndefined();
    },
  );
});

describe('shouldRetry', () => {
  const httpError = (status: number) =>
    new ApiError('http', { type: 'about:blank', title: 'x', status });

  it('never retries client errors', () => {
    expect(shouldRetry(0, httpError(404))).toBe(false);
    expect(shouldRetry(0, httpError(400))).toBe(false);
  });

  it('retries server and network errors twice', () => {
    expect(shouldRetry(0, httpError(503))).toBe(true);
    expect(shouldRetry(1, ApiError.network(new TypeError('fetch failed')))).toBe(true);
    expect(shouldRetry(2, httpError(503))).toBe(false);
  });
});

describe('authorization', () => {
  /** Registers a fake token source; returns its spies. */
  function fakeAuth(tokens: { current?: string; renewed?: string } = {}) {
    const renew = vi.fn(() => Promise.resolve(tokens.renewed));
    const reauthenticate = vi.fn();
    const auth: ApiAuth = {
      getAccessToken: () => Promise.resolve(tokens.current),
      renewAccessToken: renew,
      reauthenticate,
    };
    configureApiAuth(auth);
    return { renew, reauthenticate };
  }

  /** Answers 200 only for `accepted`, else 401, recording each Authorization header. */
  function serveHostFor(accepted: string) {
    const seen: (string | null)[] = [];
    server.use(
      http.get(HOST, ({ request }) => {
        const header = request.headers.get('Authorization');
        seen.push(header);
        return header === `Bearer ${accepted}`
          ? HttpResponse.json({ id: hostId })
          : problemResponse({ type: 'about:blank', title: 'Unauthorized', status: 401 });
      }),
    );
    return seen;
  }

  it('sends the access token as a bearer token', async () => {
    fakeAuth({ current: 'token-1' });
    const seen = serveHostFor('token-1');

    await expect(getHost()).resolves.toMatchObject({ id: hostId });
    expect(seen).toEqual(['Bearer token-1']);
  });

  it('renews once and replays the request when the API rejects the token', async () => {
    const auth = fakeAuth({ current: 'revoked', renewed: 'token-2' });
    const seen = serveHostFor('token-2');

    await expect(getHost()).resolves.toMatchObject({ id: hostId });
    expect(seen).toEqual(['Bearer revoked', 'Bearer token-2']);
    expect(auth.reauthenticate).not.toHaveBeenCalled();
  });

  it('asks for re-authentication when renewal fails, and surfaces the 401', async () => {
    const auth = fakeAuth({ current: 'revoked' });
    serveHostFor('never');

    const error = await getHost().catch((caught: unknown) => caught);

    expect(error).toMatchObject({ status: 401 });
    expect(auth.renew).toHaveBeenCalledTimes(1);
    expect(auth.reauthenticate).toHaveBeenCalledTimes(1);
  });

  it('asks for re-authentication when even a fresh token is refused', async () => {
    const auth = fakeAuth({ current: 'a', renewed: 'b' });
    const seen = serveHostFor('never');

    await getHost().catch(() => undefined);

    expect(seen).toEqual(['Bearer a', 'Bearer b']);
    expect(auth.reauthenticate).toHaveBeenCalledTimes(1);
  });

  it('never sends the token to another origin', async () => {
    fakeAuth({ current: 'secret' });
    let header: string | null = 'unset';
    server.use(
      http.get('https://elsewhere.example/api/v1/hosts', ({ request }) => {
        header = request.headers.get('Authorization');
        return HttpResponse.json(buildHostPage([]));
      }),
    );

    await api.GET('/api/v1/hosts', { baseUrl: 'https://elsewhere.example' });

    expect(header).toBeNull();
  });
});
