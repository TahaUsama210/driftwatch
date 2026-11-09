import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import type { Plugin, ProxyOptions } from 'vite';
import { defineConfig } from 'vitest/config';

const DEV_PORT = 5180;
const PREVIEW_PORT = 5181;
const LOCAL_API = 'http://localhost:8480';

const apiProxy: Record<string, ProxyOptions> = {
  '/api': { target: LOCAL_API, changeOrigin: true },
  '/actuator': { target: LOCAL_API, changeOrigin: true },
};

/**
 * Serves MSW's service worker straight from node_modules in mock mode. The worker
 * therefore always matches the installed msw version and never ships in `dist`.
 */
function mockServiceWorker(): Plugin {
  const workerPath = createRequire(import.meta.url).resolve('msw/mockServiceWorker.js');
  return {
    name: 'driftwatch:mock-service-worker',
    apply: 'serve',
    configureServer(server) {
      server.middlewares.use('/mockServiceWorker.js', (_req, res, next) => {
        readFile(workerPath).then((script) => {
          res.setHeader('Content-Type', 'text/javascript');
          res.setHeader('Cache-Control', 'no-store');
          res.end(script);
        }, next);
      });
    },
  };
}

export default defineConfig(({ mode }) => {
  const mock = mode === 'mock';
  return {
    plugins: [react(), tailwindcss(), ...(mock ? [mockServiceWorker()] : [])],
    server: {
      port: DEV_PORT,
      strictPort: true,
      proxy: mock ? {} : apiProxy,
    },
    preview: {
      port: PREVIEW_PORT,
      strictPort: true,
      proxy: apiProxy,
    },
    test: {
      environment: 'jsdom',
      setupFiles: ['./src/test/setup.ts'],
      restoreMocks: true,
      testTimeout: 20_000,
      coverage: {
        provider: 'v8',
        include: ['src/**/*.{ts,tsx}'],
        exclude: [
          'src/**/*.test.{ts,tsx}',
          'src/test/**',
          'src/mocks/**',
          'src/main.tsx',
          'src/api/schema.d.ts',
        ],
        reporter: ['text-summary', 'text', 'html', 'lcov'],
        // A floor a little under the current figures, so a regression fails `npm run coverage`.
        thresholds: { statements: 90, branches: 75, functions: 90, lines: 90 },
      },
    },
  };
});
