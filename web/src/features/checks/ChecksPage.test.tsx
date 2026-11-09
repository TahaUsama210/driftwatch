import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { CheckSummary } from '../../api/types';
import { captureGet, locationParams, renderRoute } from '../../test/render';

const catalog: CheckSummary[] = [
  {
    id: 'accounts.uid0_only_root',
    title: 'Only root has UID 0',
    category: 'accounts',
    severity: 'critical',
    counts: { pass: 3, fail: 1, error: 0, notApplicable: 0 },
  },
  {
    id: 'ssh.x11_forwarding',
    title: 'SSH X11 forwarding is disabled',
    category: 'ssh',
    severity: 'low',
    counts: { pass: 2, fail: 0, error: 0, notApplicable: 2 },
  },
];

describe('ChecksPage', () => {
  it('shows each check with its fleet status breakdown', async () => {
    captureGet('/api/v1/checks', catalog);

    renderRoute('/checks');

    const row = (await screen.findByRole('link', { name: 'Only root has UID 0' })).closest('tr');
    if (!row) throw new Error('check row not found');
    expect(
      within(row).getByRole('img', { name: '3 pass, 1 fail, 0 error, 0 not applicable' }),
    ).toBeInTheDocument();
    expect(row).toHaveTextContent('1 failing');
    expect(
      screen.getByRole('link', { name: 'SSH X11 forwarding is disabled' }).closest('tr'),
    ).toHaveTextContent('none failing');
  });

  it('filters by category and keeps the choice in the URL', async () => {
    captureGet('/api/v1/checks', catalog);
    const { user, router } = renderRoute('/checks');

    await screen.findByRole('link', { name: 'Only root has UID 0' });
    await user.click(screen.getByRole('radio', { name: 'SSH' }));

    expect(screen.queryByRole('link', { name: 'Only root has UID 0' })).not.toBeInTheDocument();
    expect(
      screen.getByRole('link', { name: 'SSH X11 forwarding is disabled' }),
    ).toBeInTheDocument();
    expect(locationParams(router)).toEqual({ category: 'ssh' });
  });
});
