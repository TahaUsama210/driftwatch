import { screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { FleetTrend } from '../../api/types';
import { captureGet, locationParams, renderRoute } from '../../test/render';

const trend: FleetTrend = {
  bucketMinutes: 15,
  points: [
    { bucketStart: '2026-11-03T13:30:00Z', averageScore: null, hostCount: 0 },
    { bucketStart: '2026-11-03T13:45:00Z', averageScore: 86.5, hostCount: 4 },
  ],
};

describe('FleetTrendCard', () => {
  it('maps each range preset to hours and bucket size, kept in the URL', async () => {
    const requests = captureGet('/api/v1/fleet/trend', trend);
    const { user, router } = renderRoute('/');

    const card = await screen.findByRole('region', { name: 'Fleet score trend' });
    await waitFor(() => {
      expect(requests.lastParams()).toEqual({ hours: '24', bucketMinutes: '15' });
    });

    await user.click(within(card).getByRole('radio', { name: '7d' }));
    await waitFor(() => {
      expect(requests.lastParams()).toEqual({ hours: '168', bucketMinutes: '120' });
    });
    expect(locationParams(router)).toEqual({ range: '7d' });

    await user.click(within(card).getByRole('radio', { name: '1h' }));
    await waitFor(() => {
      expect(requests.lastParams()).toEqual({ hours: '1', bucketMinutes: '1' });
    });
  });

  it('offers the trend as a table, including empty buckets', async () => {
    captureGet('/api/v1/fleet/trend', trend);
    const { user } = renderRoute('/');

    const card = await screen.findByRole('region', { name: 'Fleet score trend' });
    const toggle = await within(card).findByRole('button', { name: 'Table' });
    await user.click(toggle);

    const table = within(card).getByRole('table', { name: /Fleet average score/ });
    const [, newest, oldest] = within(table).getAllByRole('row');
    expect(newest).toHaveTextContent('86.5');
    expect(newest).toHaveTextContent('4 hosts reporting');
    expect(oldest).toHaveTextContent('—');
    expect(within(card).getByRole('button', { name: 'Chart' })).toHaveAttribute(
      'aria-pressed',
      'true',
    );
  });
});
