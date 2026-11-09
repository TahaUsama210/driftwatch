import { describe, expect, it } from 'vitest';
import {
  DEFAULT_HOST_LIST_PARAMS,
  hasActiveFilters,
  parseHostListParams,
  toApiQuery,
  toSearchParams,
} from './hostListParams';

const parse = (search: string) => parseHostListParams(new URLSearchParams(search));

describe('host list URL state', () => {
  it('defaults everything when the URL has no params', () => {
    expect(parse('')).toEqual(DEFAULT_HOST_LIST_PARAMS);
  });

  it('reads a full set of params, converting the page to zero-based', () => {
    expect(parse('q=web&status=STALE&sort=score&dir=desc&page=3')).toEqual({
      query: 'web',
      status: 'STALE',
      sort: 'score',
      direction: 'desc',
      page: 2,
    });
  });

  it.each([
    ['status=OFFLINE', { status: undefined }],
    ['sort=kernel', { sort: 'hostname' }],
    ['dir=sideways', { direction: 'asc' }],
    ['page=0', { page: 0 }],
    ['page=-4', { page: 0 }],
    ['page=two', { page: 0 }],
  ])('falls back to the default for invalid "%s"', (search, expected) => {
    expect(parse(search)).toMatchObject(expected);
  });

  it('writes only non-default values and round-trips', () => {
    const params = { ...DEFAULT_HOST_LIST_PARAMS, query: 'rocky', page: 1 };
    const search = toSearchParams(params);
    expect(search.toString()).toBe('q=rocky&page=2');
    expect(parseHostListParams(search)).toEqual(params);
    expect(toSearchParams(DEFAULT_HOST_LIST_PARAMS).toString()).toBe('');
  });

  it('maps to the API query, trimming the search and omitting an empty one', () => {
    expect(toApiQuery({ ...DEFAULT_HOST_LIST_PARAMS, query: '  web ' })).toMatchObject({
      query: 'web',
      page: 0,
      size: 25,
    });
    expect(toApiQuery({ ...DEFAULT_HOST_LIST_PARAMS, query: '   ' }).query).toBeUndefined();
  });

  it('knows when filters are active', () => {
    expect(hasActiveFilters(DEFAULT_HOST_LIST_PARAMS)).toBe(false);
    expect(hasActiveFilters({ ...DEFAULT_HOST_LIST_PARAMS, status: 'ONLINE' })).toBe(true);
    expect(hasActiveFilters({ ...DEFAULT_HOST_LIST_PARAMS, query: ' ' })).toBe(false);
  });
});
