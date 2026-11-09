import { screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { buildCheckDetail, buildCheckHostStatus } from '../../test/factories';
import { captureGet, renderRoute } from '../../test/render';
import { sortFailuresFirst } from './sortFailuresFirst';

const host = (hostname: string, status: 'PASS' | 'FAIL' | 'ERROR' | 'NOT_APPLICABLE') =>
  buildCheckHostStatus({ hostId: `id-${hostname}`, hostname, status });

describe('sortFailuresFirst', () => {
  it('puts FAIL and ERROR first, each group ordered by hostname', () => {
    const sorted = sortFailuresFirst([
      host('alpha', 'PASS'),
      host('zeta', 'FAIL'),
      host('beta', 'NOT_APPLICABLE'),
      host('gamma', 'ERROR'),
    ]);
    expect(sorted.map((entry) => entry.hostname)).toEqual(['gamma', 'zeta', 'alpha', 'beta']);
  });
});

describe('CheckDetailPage', () => {
  it('lists failing hosts first even when the response is unordered', async () => {
    captureGet(
      '/api/v1/checks/ssh.permit_root_login',
      buildCheckDetail({
        counts: { pass: 1, fail: 1, error: 1, notApplicable: 1 },
        hosts: [
          host('host-alpine-drifting', 'PASS'),
          host('host-ubuntu-hardened', 'FAIL'),
          host('host-fedora-batch', 'NOT_APPLICABLE'),
          host('host-debian-legacy', 'ERROR'),
        ],
      }),
    );

    renderRoute('/checks/ssh.permit_root_login');

    const table = await screen.findByRole('table', {
      name: 'Hosts and their status for this check',
    });
    const [, ...rows] = within(table).getAllByRole('row');
    expect(rows.map((row) => within(row).getByRole('link').textContent)).toEqual([
      'host-debian-legacy',
      'host-ubuntu-hardened',
      'host-alpine-drifting',
      'host-fedora-batch',
    ]);
    expect(rows[0]).toHaveTextContent('Error');
    expect(
      screen.getByRole('img', { name: '1 pass, 1 fail, 1 error, 1 not applicable' }),
    ).toBeInTheDocument();
  });
});
