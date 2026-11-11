import { createRoot } from 'react-dom/client';
import { loadOidcAuth, startApp } from './app/bootstrap';
import type { AuthController } from './auth/types';
import './styles/index.css';

/**
 * `npm run dev:mock`: the API is served in the browser by MSW and a fake user is signed
 * in (`?role=admin|viewer|none`). Vite drops this branch, and its chunks, from builds.
 */
async function loadMockAuth(): Promise<AuthController> {
  try {
    const { startMockWorker } = await import('./mocks/browser');
    await startMockWorker();
  } catch (error) {
    console.error('Driftwatch: the mock API failed to start; requests will go to /api.', error);
  }
  const { MockAuth, mockRoleFromSearch } = await import('./auth/mockAuth');
  return new MockAuth(mockRoleFromSearch(window.location.search));
}

const container = document.getElementById('root');
if (!container) throw new Error('Driftwatch: #root element is missing from index.html');

void startApp(createRoot(container), import.meta.env.MODE === 'mock' ? loadMockAuth : loadOidcAuth);
