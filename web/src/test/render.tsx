import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse, type JsonBodyType } from 'msw';
import { createMemoryRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { routes } from '../app/routes';
import { server } from './server';

/** Renders the real route tree at `path` with a fresh, non-retrying query client. */
export function renderRoute(path: string) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  const user = userEvent.setup();
  render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return { user, router, queryClient };
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
