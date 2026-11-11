import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import tailwindcss from '@tailwindcss/vite';
import react from '@vitejs/plugin-react';
import type { Connect, Plugin, ProxyOptions } from 'vite';
import { defineConfig } from 'vitest/config';

const DEV_PORT = 5180;
const PREVIEW_PORT = 5181;
const LOCAL_API = 'http://localhost:8480';
/** The compose stack's Keycloak; the realm lists http://localhost:5180 as a redirect URI and web origin. */
const LOCAL_OIDC_AUTHORITY = 'http://localhost:8400/auth/realms/driftwatch';
const LOCAL_OIDC_CLIENT_ID = 'driftwatch-web';

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

/**
 * Serves /config.json the way nginx does in the container (same shape, same env var
 * names, no-store), so `npm run dev` exercises the real runtime-config path.
 */
function runtimeConfig(): Plugin {
  const body = JSON.stringify({
    oidc: {
      authority: process.env.OIDC_AUTHORITY ?? LOCAL_OIDC_AUTHORITY,
      clientId: process.env.OIDC_CLIENT_ID ?? LOCAL_OIDC_CLIENT_ID,
    },
  });
  const serve: Connect.NextHandleFunction = (req, res, next) => {
    if (req.url?.split('?')[0] !== '/config.json') {
      next();
      return;
    }
    res.setHeader('Content-Type', 'application/json');
    res.setHeader('Cache-Control', 'no-store');
    res.end(body);
  };
  return {
    name: 'driftwatch:runtime-config',
    configureServer(server) {
      server.middlewares.use(serve);
    },
    configurePreviewServer(server) {
      server.middlewares.use(serve);
    },
  };
}

const page = (file: string) => fileURLToPath(new URL(file, import.meta.url));

export default defineConfig(({ mode }) => {
  const mock = mode === 'mock';
  return {
    plugins: [react(), tailwindcss(), mock ? mockServiceWorker() : runtimeConfig()],
    build: {
      rolldownOptions: {
        // The SPA, and the tiny page the silent sign-in iframe lands on.
        input: { main: page('./index.html'), silentRenew: page('./silent-renew.html') },
      },
    },
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
          'src/auth/silentRenew.ts',
          'src/api/schema.d.ts',
        ],
        reporter: ['text-summary', 'text', 'html', 'lcov'],
        // A floor a little under the current figures, so a regression fails `npm run coverage`.
        thresholds: { statements: 90, branches: 75, functions: 90, lines: 90 },
      },
    },
  };
});
