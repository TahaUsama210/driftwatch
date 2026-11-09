import { screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { buildHost, buildHostPage } from '../../test/factories';
import { captureGet, locationParams, renderRoute } from '../../test/render';

const HOSTS = '/api/v1/hosts';

const fleetPage = (url: URL) =>
  buildHostPage(
    [
      buildHost(),
      buildHost({
        id: 'a91d4e07-2b6c-4f38-8e5a-c7b30f19d264',
        hostname: 'host-debian-legacy',
        status: 'STALE',
      }),
    ],
    { totalItems: 60, page: Number(url.searchParams.get('page')) },
  );

describe('HostsPage', () => {
  it('sends search, status, sort and page params and mirrors them in the URL', async () => {
    const hosts = captureGet(HOSTS, fleetPage);
    const { user, router } = renderRoute('/hosts');

    expect(await screen.findByRole('link', { name: 'host-ubuntu-hardened' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'host-debian-legacy' })).toHaveAttribute(
      'href',
      '/hosts/a91d4e07-2b6c-4f38-8e5a-c7b30f19d264',
    );
    expect(hosts.lastParams()).toEqual({
      sort: 'hostname',
      direction: 'asc',
      page: '0',
      size: '25',
    });

    await user.type(screen.getByRole('searchbox', { name: 'Search' }), 'debian');
    await waitFor(() => {
      expect(hosts.lastParams()).toMatchObject({ query: 'debian', page: '0' });
    });
    expect(locationParams(router)).toEqual({ q: 'debian' });

    await user.selectOptions(screen.getByRole('combobox', { name: 'Status' }), 'STALE');
    await waitFor(() => {
      expect(hosts.lastParams()).toMatchObject({ query: 'debian', status: 'STALE' });
    });

    await user.click(screen.getByRole('button', { name: /^Score/ }));
    await waitFor(() => {
      expect(hosts.lastParams()).toMatchObject({ sort: 'score', direction: 'asc' });
    });
    await user.click(screen.getByRole('button', { name: /^Score/ }));
    await waitFor(() => {
      expect(hosts.lastParams()).toMatchObject({ sort: 'score', direction: 'desc' });
    });
    expect(screen.getByRole('columnheader', { name: /^Score/ })).toHaveAttribute(
      'aria-sort',
      'descending',
    );

    await user.click(screen.getByRole('button', { name: 'Next page' }));
    await waitFor(() => {
      expect(hosts.lastParams()).toMatchObject({ page: '1' });
    });
    expect(locationParams(router)).toEqual({
      q: 'debian',
      status: 'STALE',
      sort: 'score',
      dir: 'desc',
      page: '2',
    });
  });

  it('restores the list state from a shared URL', async () => {
    const hosts = captureGet(HOSTS, fleetPage);

    renderRoute('/hosts?q=rocky&status=ONLINE&sort=lastSeenAt&dir=desc&page=3');

    await screen.findByRole('link', { name: 'host-ubuntu-hardened' });
    expect(hosts.lastParams()).toEqual({
      query: 'rocky',
      status: 'ONLINE',
      sort: 'lastSeenAt',
      direction: 'desc',
      page: '2',
      size: '25',
    });
    expect(screen.getByRole('searchbox', { name: 'Search' })).toHaveValue('rocky');
    expect(screen.getByRole('combobox', { name: 'Status' })).toHaveValue('ONLINE');
  });

  it('offers to clear filters when nothing matches', async () => {
    captureGet(HOSTS, buildHostPage([]));
    const { user, router } = renderRoute('/hosts?q=nothing-matches');

    await user.click(await screen.findByRole('button', { name: 'Clear filters' }));

    expect(locationParams(router)).toEqual({});
  });

  it('offers the first page when the URL points past the last one', async () => {
    const hosts = captureGet(HOSTS, buildHostPage([], { page: 4, totalItems: 2 }));
    const { user, router } = renderRoute('/hosts?page=5');

    await user.click(await screen.findByRole('button', { name: 'Go to the first page' }));

    expect(screen.queryByRole('button', { name: 'Clear filters' })).not.toBeInTheDocument();
    expect(locationParams(router)).toEqual({});
    await waitFor(() => {
      expect(hosts.lastParams()).toMatchObject({ page: '0' });
    });
  });

  it('shows how to enroll an agent when no host has enrolled', async () => {
    captureGet(HOSTS, buildHostPage([]));

    renderRoute('/hosts');

    expect(
      await screen.findByRole('heading', { name: 'No hosts enrolled yet' }),
    ).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });
});
