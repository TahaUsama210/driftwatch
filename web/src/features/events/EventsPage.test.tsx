import { screen, waitFor } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { buildDriftEvent } from '../../test/factories';
import { captureGet, locationParams, renderRoute } from '../../test/render';

describe('EventsPage', () => {
  it('filters by direction and links each event to its host and check', async () => {
    const events = captureGet('/api/v1/events', [buildDriftEvent()]);
    const { user, router } = renderRoute('/events');

    expect(await screen.findByRole('link', { name: 'SSH root login is disabled' })).toHaveAttribute(
      'href',
      '/checks/ssh.permit_root_login',
    );
    expect(screen.getByRole('link', { name: 'host-ubuntu-hardened' })).toHaveAttribute(
      'href',
      '/hosts/3f6c1a52-8d0e-4b7a-9c21-5e8f0d4a7b13',
    );
    expect(events.lastParams()).toEqual({ limit: '100' });

    await user.click(screen.getByRole('radio', { name: 'Resolved' }));

    await waitFor(() => {
      expect(events.lastParams()).toEqual({ direction: 'RESOLVED', limit: '100' });
    });
    expect(locationParams(router)).toEqual({ direction: 'RESOLVED' });
  });

  it('shows an empty state when nothing matches', async () => {
    captureGet('/api/v1/events', []);

    renderRoute('/events?direction=CHANGED');

    expect(await screen.findByText('No drift events')).toBeInTheDocument();
    expect(screen.getByText('Nothing matches these filters.')).toBeInTheDocument();
  });
});
