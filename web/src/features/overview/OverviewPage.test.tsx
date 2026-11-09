import { screen, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it } from 'vitest';
import { problemResponse } from '../../mocks/handlers';
import { buildFleetSummary } from '../../test/factories';
import { renderRoute } from '../../test/render';
import { server } from '../../test/server';

const SUMMARY = '/api/v1/fleet/summary';

function tile(label: string) {
  const term = screen.getByText(label, { selector: 'dt' });
  const container = term.parentElement;
  if (!container) throw new Error(`No tile for ${label}`);
  return within(container);
}

describe('OverviewPage', () => {
  it('renders the headline numbers from the fleet summary', async () => {
    server.use(
      http.get(SUMMARY, () =>
        HttpResponse.json(
          buildFleetSummary({
            hostCount: 5,
            onlineCount: 4,
            staleCount: 1,
            averageScore: 84.6,
            hostsWithCriticalFailures: 2,
            topFailingChecks: [
              {
                checkId: 'accounts.uid0_only_root',
                title: 'Only root has UID 0',
                category: 'accounts',
                severity: 'critical',
                failingHosts: 3,
              },
            ],
          }),
        ),
      ),
    );

    renderRoute('/');

    expect(await screen.findByRole('heading', { name: 'Fleet overview' })).toBeInTheDocument();
    await screen.findByText('Fleet average score', { selector: 'dt' });
    expect(tile('Fleet average score').getByText('84.6')).toBeInTheDocument();
    expect(tile('Fleet average score').getByText('Needs attention')).toBeInTheDocument();
    expect(tile('Hosts online').getByText('4')).toBeInTheDocument();
    expect(tile('Hosts online').getByText('of 5')).toBeInTheDocument();
    expect(tile('Hosts online').getByRole('link', { name: '1 stale host' })).toHaveAttribute(
      'href',
      '/hosts?status=STALE',
    );
    expect(tile('Hosts with critical failures').getByText('2')).toBeInTheDocument();

    const topFailing = screen.getByRole('region', { name: 'Top failing checks' });
    expect(within(topFailing).getByRole('link', { name: 'Only root has UID 0' })).toHaveAttribute(
      'href',
      '/checks/accounts.uid0_only_root',
    );
    expect(topFailing).toHaveTextContent('3 of 5 hosts');
  });

  it('explains how to enroll an agent when the fleet is empty', async () => {
    server.use(
      http.get(SUMMARY, () =>
        HttpResponse.json(
          buildFleetSummary({
            hostCount: 0,
            onlineCount: 0,
            averageScore: null,
            lastScanAt: null,
          }),
        ),
      ),
    );

    renderRoute('/');

    expect(
      await screen.findByRole('heading', { name: 'No hosts enrolled yet' }),
    ).toBeInTheDocument();
    const docker = screen.getByRole('figure', { name: 'Docker command' });
    expect(docker).toHaveTextContent('DRIFTWATCH_SERVER_URL=');
    expect(docker).toHaveTextContent('DRIFTWATCH_ENROLLMENT_TOKEN=');
    expect(screen.getByRole('figure', { name: 'Shell commands' })).toHaveTextContent(
      'driftwatch-agent run',
    );
    expect(screen.queryByText('Fleet average score')).not.toBeInTheDocument();
  });

  it('keeps the last good data on screen when a background refresh fails', async () => {
    server.use(
      http.get(SUMMARY, () => HttpResponse.json(buildFleetSummary({ averageScore: 84.6 }))),
    );
    const { queryClient } = renderRoute('/');
    await screen.findByText('Fleet average score', { selector: 'dt' });

    server.use(
      http.get(SUMMARY, () =>
        problemResponse({
          type: 'about:blank',
          title: 'Service Unavailable',
          status: 503,
          detail: 'The API cannot reach its database.',
        }),
      ),
    );
    await queryClient.refetchQueries({ queryKey: ['fleet', 'summary'] });

    expect(await screen.findByText(/Refresh failed/)).toBeInTheDocument();
    expect(tile('Fleet average score').getByText('84.6')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('shows the problem details of a failed request and recovers on retry', async () => {
    server.use(
      http.get(
        SUMMARY,
        () =>
          problemResponse({
            type: 'about:blank',
            title: 'Service Unavailable',
            status: 503,
            detail: 'The API cannot reach its database.',
          }),
        { once: true },
      ),
    );

    const { user } = renderRoute('/');

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Service Unavailable');
    expect(alert).toHaveTextContent('The API cannot reach its database.');
    expect(alert).toHaveTextContent('HTTP 503');

    await user.click(within(alert).getByRole('button', { name: 'Retry' }));

    expect(await screen.findByText('Fleet average score', { selector: 'dt' })).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
