import { QueryClient } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse, type JsonBodyType } from 'msw';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { configureApiAuth } from '../api/client';
import { SessionProviders } from '../app/App';
import { routes } from '../app/routes';
import { AuthContext } from '../auth/AuthContext';
import { AuthGate } from '../auth/AuthGate';
import { MockAuth } from '../auth/mockAuth';
import type { AuthController, MockRole } from '../auth/types';
import { server } from './server';

interface RenderRouteOptions {
  /** Signed-in mock user; viewer by default. Ignored when `auth` is given. */
  role?: MockRole;
  auth?: AuthController;
}

/**
 * Renders the real route tree at `path` behind the real auth gate, with a fresh,
 * non-retrying query client. API calls carry the mock user's bearer token, which the
 * MSW handlers check exactly like the API does.
 */
export function renderRoute(path: string, { role = 'viewer', auth }: RenderRouteOptions = {}) {
  const controller = auth ?? new MockAuth(role);
  configureApiAuth(controller);
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  const user = userEvent.setup();
  render(
    <AuthContext value={controller}>
      <AuthGate>
        <SessionProviders queryClient={queryClient}>
          <RouterProvider router={router} />
        </SessionProviders>
      </AuthGate>
    </AuthContext>,
  );
  return { user, router, queryClient, auth: controller };
}

/** The current URL's query string as a plain object, for asserting URL state. */
export function locationParams(router: ReturnType<typeof createMemoryRouter>) {
  return Object.fromEntries(new URLSearchParams(router.state.location.search));
}

/**
 * Serves `body` for GET `path` and records every request URL, so tests can assert on
 * the exact query parameters the UI sent.
 */
export function captureGet(path: string, body: JsonBodyType | ((url: URL) => JsonBodyType)) {
  const requests: URL[] = [];
  // JsonBodyType admits any object, so narrow the "computed from the URL" form explicitly.
  const respond = typeof body === 'function' ? (body as (url: URL) => JsonBodyType) : () => body;
  server.use(
    http.get(path, ({ request }) => {
      const url = new URL(request.url);
      requests.push(url);
      return HttpResponse.json(respond(url));
    }),
  );
  return {
    requests,
    /** Query params of the most recent request. */
    lastParams: () => Object.fromEntries(requests.at(-1)?.searchParams ?? []),
  };
}
