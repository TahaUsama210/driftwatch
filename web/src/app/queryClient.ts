import { QueryClient } from '@tanstack/react-query';
import { isApiError } from '../api/client';

const MAX_RETRIES = 2;

/** Client errors (4xx) are answers, not glitches: retrying a 404 cannot succeed. */
export function shouldRetry(failureCount: number, error: unknown): boolean {
  const status = isApiError(error) ? error.status : undefined;
  if (status !== undefined && status >= 400 && status < 500) return false;
  return failureCount < MAX_RETRIES;
}

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 10_000,
        retry: shouldRetry,
      },
    },
  });
}
