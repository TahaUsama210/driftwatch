import type { RouteObject } from 'react-router';
import { AppShell } from './AppShell';
import { PageFallback } from './PageFallback';
import { RouteError } from './RouteError';

/** Pages are code-split per route; the chart library only loads where it is used. */
export const routes: RouteObject[] = [
  {
    path: '/',
    Component: AppShell,
    HydrateFallback: PageFallback,
    children: [
      {
        ErrorBoundary: RouteError,
        children: [
          {
            index: true,
            lazy: async () => ({
              Component: (await import('../features/overview/OverviewPage')).OverviewPage,
            }),
          },
          {
            path: 'hosts',
            lazy: async () => ({
              Component: (await import('../features/hosts/HostsPage')).HostsPage,
            }),
          },
          {
            path: 'hosts/:hostId',
            lazy: async () => ({
              Component: (await import('../features/hosts/detail/HostDetailPage')).HostDetailPage,
            }),
          },
          {
            path: 'checks',
            lazy: async () => ({
              Component: (await import('../features/checks/ChecksPage')).ChecksPage,
            }),
          },
          {
            path: 'checks/:checkId',
            lazy: async () => ({
              Component: (await import('../features/checks/CheckDetailPage')).CheckDetailPage,
            }),
          },
          {
            path: 'events',
            lazy: async () => ({
              Component: (await import('../features/events/EventsPage')).EventsPage,
            }),
          },
          {
            path: '*',
            lazy: async () => ({
              Component: (await import('../features/not-found/NotFoundPage')).NotFoundPage,
            }),
          },
        ],
      },
    ],
  },
];
