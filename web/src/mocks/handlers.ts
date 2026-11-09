import { http, HttpResponse, type JsonBodyType, type RequestHandler } from 'msw';
import type {
  DriftDirection,
  HostListQuery,
  HostSortField,
  HostStatus,
  Problem,
} from '../api/types';
import { MockApiError, type MockFleet } from './fleet';

/** An RFC 9457 response, as the real API sends for every error. */
export function problemResponse(problem: Problem): HttpResponse<Problem> {
  return HttpResponse.json(problem, {
    status: problem.status,
    headers: { 'Content-Type': 'application/problem+json' },
  });
}

function toProblem(error: MockApiError): Problem {
  return {
    type: 'about:blank',
    title: error.title,
    status: error.status,
    detail: error.message,
    ...(error.errors ? { errors: error.errors } : {}),
  };
}

function intParam(url: URL, name: string): number | undefined {
  const raw = url.searchParams.get(name);
  return raw === null ? undefined : Number(raw);
}

function oneOf<T extends string>(value: string | null, allowed: readonly T[]): T | undefined {
  return allowed.find((candidate) => candidate === value);
}

interface HandlerOptions {
  /** Awaited before every response, e.g. to simulate network latency in the browser. */
  latency?: () => Promise<void>;
}

/** Every read endpoint of contracts/openapi.yaml, answered by a {@link MockFleet}. */
export function createHandlers(
  fleet: MockFleet,
  { latency }: HandlerOptions = {},
): RequestHandler[] {
  /** Runs a fleet query, turning MockApiError into the matching problem response. */
  const respond = async (produce: () => unknown) => {
    await latency?.();
    try {
      return HttpResponse.json(produce() as JsonBodyType);
    } catch (error) {
      if (error instanceof MockApiError) return problemResponse(toProblem(error));
      throw error;
    }
  };

  return [
    http.get('/api/v1/fleet/summary', () => respond(() => fleet.summary())),

    http.get('/api/v1/fleet/trend', ({ request }) => {
      const url = new URL(request.url);
      return respond(() =>
        fleet.trend(intParam(url, 'hours') ?? 24, intParam(url, 'bucketMinutes') ?? 60),
      );
    }),

    http.get('/api/v1/hosts', ({ request }) => {
      const url = new URL(request.url);
      const query: HostListQuery = {
        query: url.searchParams.get('query') ?? undefined,
        status: oneOf<HostStatus>(url.searchParams.get('status'), ['ONLINE', 'STALE']),
        sort: oneOf<HostSortField>(url.searchParams.get('sort'), [
          'hostname',
          'score',
          'lastSeenAt',
        ]),
        direction: url.searchParams.get('direction') === 'desc' ? 'desc' : 'asc',
        page: intParam(url, 'page'),
        size: intParam(url, 'size'),
      };
      return respond(() => fleet.listHosts(query));
    }),

    http.get<{ hostId: string }>('/api/v1/hosts/:hostId', ({ params }) =>
      respond(() => fleet.getHost(params.hostId)),
    ),

    http.get<{ hostId: string }>('/api/v1/hosts/:hostId/scans', ({ params, request }) => {
      const limit = intParam(new URL(request.url), 'limit');
      return respond(() => fleet.hostScans(params.hostId, limit));
    }),

    http.get<{ hostId: string }>('/api/v1/hosts/:hostId/drift', ({ params, request }) => {
      const url = new URL(request.url);
      return respond(() =>
        fleet.drift(params.hostId, {
          from: url.searchParams.get('from') ?? undefined,
          to: url.searchParams.get('to') ?? undefined,
        }),
      );
    }),

    http.get<{ scanId: string }>('/api/v1/scans/:scanId', ({ params }) =>
      respond(() => fleet.getScan(params.scanId)),
    ),

    http.get('/api/v1/checks', () => respond(() => fleet.checks())),

    http.get<{ checkId: string }>('/api/v1/checks/:checkId', ({ params }) =>
      respond(() => fleet.getCheck(params.checkId)),
    ),

    http.get('/api/v1/events', ({ request }) => {
      const url = new URL(request.url);
      return respond(() =>
        fleet.events({
          hostId: url.searchParams.get('hostId') ?? undefined,
          direction: oneOf<DriftDirection>(url.searchParams.get('direction'), [
            'REGRESSED',
            'RESOLVED',
            'CHANGED',
          ]),
          limit: intParam(url, 'limit'),
        }),
      );
    }),
  ];
}

/** Every API call fails as if the API's database were down (the `?scenario=error` demo). */
export function createUnavailableHandlers(): RequestHandler[] {
  return [
    http.all('/api/*', () =>
      problemResponse({
        type: 'about:blank',
        title: 'Service Unavailable',
        status: 503,
        detail: 'The API cannot reach its database. Try again in a moment.',
      }),
    ),
  ];
}
