import { useCallback } from 'react';
import { useSearchParams } from 'react-router';
import type { HostListQuery, HostSortField, HostStatus, SortDirection } from '../../api/types';
import { HOST_STATUS_ORDER } from '../../domain/hostStatus';

export const HOSTS_PAGE_SIZE = 25;

const SORT_FIELDS = ['hostname', 'score', 'lastSeenAt'] as const satisfies readonly HostSortField[];

/** The host list's filters as they live in the URL (`?q=&status=&sort=&dir=&page=`). */
export interface HostListParams {
  query: string;
  status: HostStatus | undefined;
  sort: HostSortField;
  direction: SortDirection;
  /** Zero-based; the URL shows it one-based. */
  page: number;
}

export const DEFAULT_HOST_LIST_PARAMS: HostListParams = {
  query: '',
  status: undefined,
  sort: 'hostname',
  direction: 'asc',
  page: 0,
};

/** Reads params leniently: anything unrecognised falls back to its default. */
export function parseHostListParams(search: URLSearchParams): HostListParams {
  const status = HOST_STATUS_ORDER.find((value) => value === search.get('status'));
  const sort = SORT_FIELDS.find((value) => value === search.get('sort'));
  const pageNumber = Number.parseInt(search.get('page') ?? '', 10);
  return {
    // Kept verbatim (not trimmed) so the search box can round-trip what was typed.
    query: search.get('q') ?? '',
    status,
    sort: sort ?? DEFAULT_HOST_LIST_PARAMS.sort,
    direction: search.get('dir') === 'desc' ? 'desc' : 'asc',
    page: Number.isInteger(pageNumber) && pageNumber > 1 ? pageNumber - 1 : 0,
  };
}

/** Writes only non-default values so shared URLs stay short. */
export function toSearchParams(params: HostListParams): URLSearchParams {
  const search = new URLSearchParams();
  if (params.query) search.set('q', params.query);
  if (params.status) search.set('status', params.status);
  if (params.sort !== DEFAULT_HOST_LIST_PARAMS.sort) search.set('sort', params.sort);
  if (params.direction !== DEFAULT_HOST_LIST_PARAMS.direction) search.set('dir', params.direction);
  if (params.page > 0) search.set('page', String(params.page + 1));
  return search;
}

export function toApiQuery(params: HostListParams): HostListQuery {
  return {
    query: params.query.trim() || undefined,
    status: params.status,
    sort: params.sort,
    direction: params.direction,
    page: params.page,
    size: HOSTS_PAGE_SIZE,
  };
}

export function hasActiveFilters(params: HostListParams): boolean {
  return params.query.trim() !== '' || params.status !== undefined;
}

/**
 * URL-backed list state. Changing a filter or the sort returns to the first page;
 * typing in the search box replaces the history entry instead of pushing one per key.
 */
export function useHostListParams() {
  const [searchParams, setSearchParams] = useSearchParams();
  const params = parseHostListParams(searchParams);

  const update = useCallback(
    (patch: Partial<HostListParams>, options: { replace?: boolean } = {}) => {
      setSearchParams(
        (current) => {
          const previous = parseHostListParams(current);
          const resetsPage = Object.keys(patch).some((key) => key !== 'page');
          return toSearchParams({
            ...previous,
            ...patch,
            page: patch.page ?? (resetsPage ? 0 : previous.page),
          });
        },
        { replace: options.replace ?? false },
      );
    },
    [setSearchParams],
  );

  return [params, update] as const;
}
