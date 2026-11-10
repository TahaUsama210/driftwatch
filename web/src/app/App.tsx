import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import { useState, type ReactNode } from 'react';
import { createBrowserRouter } from 'react-router';
import { RouterProvider } from 'react-router/dom';
import { AuthContext } from '../auth/AuthContext';
import { AuthGate } from '../auth/AuthGate';
import type { AuthController } from '../auth/types';
import { ToastProvider } from '../components/Toast';
import { createQueryClient } from './queryClient';
import { routes } from './routes';

export function App({ auth }: { auth: AuthController }) {
  return (
    <AuthContext value={auth}>
      <AuthGate>
        <Session />
      </AuthGate>
    </AuthContext>
  );
}

/**
 * Everything that belongs to one signed-in user. Created when the gate opens, so the
 * router reads the URL after the sign-in callback has replaced it with the return path.
 */
function Session() {
  const [queryClient] = useState(createQueryClient);
  const [router] = useState(() => createBrowserRouter(routes));
  return (
    <SessionProviders queryClient={queryClient}>
      <RouterProvider router={router} />
    </SessionProviders>
  );
}

export function SessionProviders({
  queryClient,
  children,
}: {
  queryClient: QueryClient;
  children: ReactNode;
}) {
  return (
    <QueryClientProvider client={queryClient}>
      <ToastProvider>{children}</ToastProvider>
    </QueryClientProvider>
  );
}
