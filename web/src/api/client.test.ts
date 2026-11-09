import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { shouldRetry } from '../app/queryClient';
import { problemResponse } from '../mocks/handlers';
import { server } from '../test/server';
import { api, ApiError, parseProblem, unwrap } from './client';

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
