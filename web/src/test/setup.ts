import '@testing-library/jest-dom/vitest';
import { cleanup, configure } from '@testing-library/react';
import { afterAll, afterEach, beforeAll } from 'vitest';
import { server } from './server';

// Pages are lazy route chunks; the first render in a file waits for Vite to transform
// them (Recharts included), which can exceed the 1s default on a loaded CI runner.
configure({ asyncUtilTimeout: 10_000 });

/** jsdom has no layout engine, so nothing ever resizes; Recharts only needs the API. */
class ResizeObserverStub implements ResizeObserver {
  observe(): void {
    // No layout in jsdom: there is nothing to observe.
  }
  unobserve(): void {
    // See observe().
  }
  disconnect(): void {
    // See observe().
  }
}
globalThis.ResizeObserver = ResizeObserverStub;

beforeAll(() => {
  server.listen({ onUnhandledRequest: 'error' });
});

afterEach(() => {
  cleanup();
  server.resetHandlers();
  window.localStorage.clear();
});

afterAll(() => {
  server.close();
});
