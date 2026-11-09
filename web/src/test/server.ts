import { setupServer } from 'msw/node';
import { createDemoFleet } from '../mocks/fleet';
import { createHandlers } from '../mocks/handlers';

/** A frozen clock for the default fleet, so its data is identical on every run. */
export const TEST_NOW = Date.parse('2026-11-03T14:00:00Z');

/**
 * Default handlers answer every endpoint from the demo fleet; tests override the
 * endpoints whose payloads they assert on with `server.use(...)`.
 */
export const server = setupServer(
  ...createHandlers(createDemoFleet({ anchor: TEST_NOW, now: () => TEST_NOW })),
);
