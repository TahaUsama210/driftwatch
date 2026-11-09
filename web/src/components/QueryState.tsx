import type { UseQueryResult } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { ErrorState } from './ErrorState';

interface QueryStateProps<T> {
  query: UseQueryResult<T>;
  loading: ReactNode;
  children: (data: T) => ReactNode;
}

/**
 * The loading / error / data branches every query view needs. Once data has loaded it
 * stays on screen during background refetches; a failed refetch does not blank it.
 */
export function QueryState<T>({ query, loading, children }: QueryStateProps<T>) {
  if (query.isPending) return loading;
  // Only a failed first load replaces the view. A failed background refetch sets
  // isError too, but keeps the last good data, which stays on screen.
  if (query.isLoadingError) {
    return (
      <ErrorState
        error={query.error}
        retrying={query.isFetching}
        onRetry={() => void query.refetch()}
      />
    );
  }
  return children(query.data);
}
