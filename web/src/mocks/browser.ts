import { delay } from 'msw';
import { setupWorker } from 'msw/browser';
import { createDemoFleet, MockFleet } from './fleet';
import { createHandlers, createUnavailableHandlers } from './handlers';

/**
 * Starts the in-browser mock API for `npm run dev:mock`. Pick a scenario with a query
 * parameter on first load: `?scenario=empty` (no hosts) or `?scenario=error` (API down).
 */
const SCENARIOS = ['demo', 'empty', 'error'] as const;
type Scenario = (typeof SCENARIOS)[number];

/** Enough delay for loading states to be visible without making the demo sluggish. */
const latency = () => delay(150 + Math.round(Math.random() * 250));

function currentScenario(): Scenario {
  const requested = new URLSearchParams(window.location.search).get('scenario');
  return SCENARIOS.find((scenario) => scenario === requested) ?? 'demo';
}

function handlersFor(scenario: Scenario) {
  switch (scenario) {
    case 'demo':
      return createHandlers(createDemoFleet(), { latency });
    case 'empty':
      return createHandlers(new MockFleet({ hosts: [], anchor: Date.now() }), { latency });
    case 'error':
      return createUnavailableHandlers();
  }
}

export async function startMockWorker(): Promise<void> {
  const scenario = currentScenario();
  const worker = setupWorker(...handlersFor(scenario));
  await worker.start({
    onUnhandledRequest: 'bypass',
    serviceWorker: { url: '/mockServiceWorker.js' },
    quiet: true,
  });
  console.info(`Driftwatch: mock API active (scenario "${scenario}").`);
}
