import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './app/App';
import './styles/index.css';

/** `npm run dev:mock` serves the API from an in-browser MSW worker; other modes hit /api. */
async function startMockApi(): Promise<void> {
  if (import.meta.env.MODE !== 'mock') return;
  const { startMockWorker } = await import('./mocks/browser');
  await startMockWorker();
}

const container = document.getElementById('root');
if (!container) throw new Error('Driftwatch: #root element is missing from index.html');

startMockApi()
  .catch((error: unknown) => {
    console.error('Driftwatch: the mock API failed to start; requests will go to /api.', error);
  })
  .finally(() => {
    createRoot(container).render(
      <StrictMode>
        <App />
      </StrictMode>,
    );
  });
