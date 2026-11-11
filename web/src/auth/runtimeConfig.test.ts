import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { server } from '../test/server';
import { loadRuntimeConfig, RuntimeConfigError } from './runtimeConfig';

const serveConfig = (response: () => Response) => {
  server.use(http.get('/config.json', response));
};

describe('loadRuntimeConfig', () => {
  it('reads the OIDC settings and trims a trailing slash from the issuer', async () => {
    serveConfig(() =>
      HttpResponse.json({
        oidc: { authority: 'http://localhost:8400/auth/realms/driftwatch/', clientId: 'web' },
      }),
    );

    await expect(loadRuntimeConfig()).resolves.toEqual({
      oidc: { authority: 'http://localhost:8400/auth/realms/driftwatch', clientId: 'web' },
    });
  });

  it.each([
    ['an HTTP error', () => new HttpResponse('nope', { status: 404 }), /returned HTTP 404/],
    ['a network failure', () => HttpResponse.error(), /could not be fetched/],
    ['a non-JSON body', () => new HttpResponse('<html>', { status: 200 }), /not valid JSON/],
    ['a missing oidc object', () => HttpResponse.json({}), /no "oidc" object/],
    [
      'a non-URL authority',
      () => HttpResponse.json({ oidc: { authority: 'javascript:alert(1)', clientId: 'web' } }),
      /must be an http\(s\) URL/,
    ],
    [
      'an empty client ID',
      () => HttpResponse.json({ oidc: { authority: 'https://idp.example', clientId: ' ' } }),
      /non-empty string/,
    ],
  ])('rejects %s', async (_case, response, message) => {
    serveConfig(response);

    const error = await loadRuntimeConfig().catch((caught: unknown) => caught);

    expect(error).toBeInstanceOf(RuntimeConfigError);
    expect((error as Error).message).toMatch(message);
  });
});
