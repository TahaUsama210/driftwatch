import { StrictMode } from 'react';
import type { Root } from 'react-dom/client';
import { configureApiAuth } from '../api/client';
import { AuthSplash } from '../auth/AuthScreens';
import { createOidcAuth } from '../auth/oidcAuth';
import { loadRuntimeConfig } from '../auth/runtimeConfig';
import type { AuthController } from '../auth/types';
import { App } from './App';
import { StartupError } from './StartupError';

/**
 * Starts the app: obtain an auth controller, wire it into the API client, render, then
 * resolve the session (sign-in callback or silent restore). This runs outside React so
 * the one-time callback is processed exactly once, StrictMode or not.
 */
export async function startApp(root: Root, loadAuth: () => Promise<AuthController>) {
  root.render(<AuthSplash />);
  let auth: AuthController;
  try {
    auth = await loadAuth();
  } catch (error) {
    console.error('Driftwatch: startup failed.', error);
    root.render(<StartupError error={error} />);
    return;
  }
  configureApiAuth(auth);
  root.render(
    <StrictMode>
      <App auth={auth} />
    </StrictMode>,
  );
  await auth.init();
}

/** Production and `npm run dev`: OIDC settings come from /config.json at runtime. */
export async function loadOidcAuth(): Promise<AuthController> {
  return createOidcAuth(await loadRuntimeConfig());
}
