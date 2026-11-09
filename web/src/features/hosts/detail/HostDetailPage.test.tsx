import { screen, waitFor, within } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { describe, expect, it, onTestFinished, vi } from 'vitest';
import { LIVE_REFRESH_MS } from '../../../lib/config';
import { problemResponse } from '../../../mocks/handlers';
import {
  buildCheckResult,
  buildDriftChange,
  buildDriftReport,
  buildHost,
  buildScanDetail,
  buildScanSummary,
  HOST_ID,
} from '../../../test/factories';
import { captureGet, locationParams, renderRoute } from '../../../test/render';
import { server } from '../../../test/server';

const scanA = buildScanSummary({
  id: '00000001-5ca0-4000-8000-00000000000a',
  finishedAt: '2026-11-03T13:59:00Z',
  score: 81.0,
});
const scanB = buildScanSummary({
  id: '00000001-5ca0-4000-8000-00000000000b',
  finishedAt: '2026-11-03T13:59:30Z',
  score: 95.2,
});
const scanC = buildScanSummary({
  id: '00000001-5ca0-4000-8000-00000000000c',
  finishedAt: '2026-11-03T14:00:00Z',
  score: 88.1,
});

const resolvedX11 = buildDriftChange({
  checkId: 'ssh.x11_forwarding',
  title: 'SSH X11 forwarding is disabled',
  severity: 'low',
  fromStatus: 'FAIL',
  toStatus: 'PASS',
  direction: 'RESOLVED',
});
const regressedRootLogin = buildDriftChange();

function serveHost() {
  captureGet(`/api/v1/hosts/${HOST_ID}`, buildHost({ latestScan: scanC }));
  captureGet(`/api/v1/hosts/${HOST_ID}/scans`, [scanC, scanB, scanA]);
  captureGet(
    `/api/v1/scans/${scanC.id}`,
    buildScanDetail({
      ...scanC,
      results: [
        buildCheckResult(),
        buildCheckResult({
          checkId: 'accounts.uid0_only_root',
          title: 'Only root has UID 0',
          category: 'accounts',
          severity: 'critical',
          status: 'FAIL',
          expected: 'root',
          actual: 'root, toor',
          evidence: '/etc/passwd:24: toor:x:0:0::/root:/bin/sh',
        }),
      ],
    }),
  );
  captureGet('/api/v1/events', []);
  return captureGet(`/api/v1/hosts/${HOST_ID}/drift`, (url) =>
    url.searchParams.get('from') === scanA.id
      ? buildDriftReport({ fromScan: scanA, toScan: scanC, changes: [resolvedX11] })
      : // Deliberately out of order: the panel must still lead with the regression.
        buildDriftReport({
          fromScan: scanB,
          toScan: scanC,
          changes: [resolvedX11, regressedRootLogin],
        }),
  );
}

describe('HostDetailPage', () => {
  it('leads the drift panel with regressions and compares any two scans', async () => {
    const drift = serveHost();
    const { user, router } = renderRoute(`/hosts/${HOST_ID}`);

    const panel = await screen.findByRole('region', { name: 'Drift between scans' });
    const rows = await within(panel).findAllByRole('listitem');
    const changeRows = rows.filter((row) => row.dataset.direction);
    expect(changeRows.map((row) => row.dataset.direction)).toEqual(['REGRESSED', 'RESOLVED']);
    expect(changeRows[0]).toHaveTextContent('SSH root login is disabled');
    expect(changeRows[0]).toHaveTextContent('changed from Pass to Fail');
    expect(within(panel).getByRole('list', { name: 'Changes by direction' })).toHaveTextContent(
      '1 regressed1 resolved0 changed',
    );
    expect(within(panel).getByRole('combobox', { name: 'Baseline scan' })).toHaveValue(scanB.id);
    expect(within(panel).getByRole('combobox', { name: 'Compared scan' })).toHaveValue(scanC.id);

    await user.selectOptions(
      within(panel).getByRole('combobox', { name: 'Baseline scan' }),
      scanA.id,
    );

    await waitFor(() => {
      expect(drift.lastParams()).toEqual({ from: scanA.id, to: scanC.id });
    });
    await waitFor(() => {
      expect(within(panel).queryByText('SSH root login is disabled')).not.toBeInTheDocument();
    });
    expect(within(panel).getByText('SSH X11 forwarding is disabled')).toBeInTheDocument();
    expect(locationParams(router)).toEqual({ from: scanA.id, to: scanC.id });
  });

  it('filters latest results by status and expands one to show its evidence', async () => {
    serveHost();
    const { user } = renderRoute(`/hosts/${HOST_ID}`);

    const results = await screen.findByRole('region', { name: 'Latest results' });
    const failing = await within(results).findByRole('button', { name: /Only root has UID 0/ });
    expect(within(results).getByRole('button', { name: /SSH root login/ })).toBeInTheDocument();

    await user.click(within(results).getByRole('radio', { name: /Fail/ }));
    expect(
      within(results).queryByRole('button', { name: /SSH root login/ }),
    ).not.toBeInTheDocument();

    expect(failing).toHaveAttribute('aria-expanded', 'false');
    await user.click(failing);
    expect(failing).toHaveAttribute('aria-expanded', 'true');
    expect(within(results).getByText('root, toor')).toBeVisible();
    expect(within(results).getByText('/etc/passwd:24: toor:x:0:0::/root:/bin/sh')).toBeVisible();
  });

  it('follows new scans: the default comparison polls and open results stay open', async () => {
    // Real time still flows (so MSW and user-event work); polls fire when advanced.
    vi.useFakeTimers({ shouldAdvanceTime: true });
    onTestFinished(() => {
      vi.useRealTimers();
    });
    serveHost();
    const { user } = renderRoute(`/hosts/${HOST_ID}`);
    const panel = await screen.findByRole('region', { name: 'Drift between scans' });
    await within(panel).findByRole('combobox', { name: 'Compared scan' });
    const results = await screen.findByRole('region', { name: 'Latest results' });
    await user.click(await within(results).findByRole('button', { name: /Only root has UID 0/ }));

    // The host reports scan D. Its results arrive only when the test releases them.
    const scanD = buildScanSummary({
      id: '00000001-5ca0-4000-8000-00000000000d',
      finishedAt: '2026-11-03T14:00:30Z',
      score: 100,
    });
    let releaseScanD: () => void = () => undefined;
    const scanDReleased = new Promise<void>((resolve) => {
      releaseScanD = resolve;
    });
    server.use(
      http.get(`/api/v1/hosts/${HOST_ID}`, () =>
        HttpResponse.json(buildHost({ latestScan: scanD })),
      ),
      http.get(`/api/v1/hosts/${HOST_ID}/scans`, () =>
        HttpResponse.json([scanD, scanC, scanB, scanA]),
      ),
      http.get(`/api/v1/scans/${scanD.id}`, async () => {
        await scanDReleased;
        return HttpResponse.json(buildScanDetail({ ...scanD, results: [buildCheckResult()] }));
      }),
      http.get(`/api/v1/hosts/${HOST_ID}/drift`, () =>
        HttpResponse.json(buildDriftReport({ fromScan: scanC, toScan: scanD, changes: [] })),
      ),
    );
    await vi.advanceTimersByTimeAsync(LIVE_REFRESH_MS);

    // The default "previous vs latest" comparison polls with the rest of the page.
    await waitFor(() => {
      expect(within(panel).getByRole('combobox', { name: 'Compared scan' })).toHaveDisplayValue(
        /score 100\.0/,
      );
    });
    // While scan D loads, scan C's results (and the expanded row) stay on screen.
    expect(within(results).getByText('root, toor')).toBeVisible();
    expect(within(results).queryByRole('status')).not.toBeInTheDocument();

    releaseScanD();
    await waitFor(() => {
      expect(
        within(results).queryByRole('button', { name: /Only root has UID 0/ }),
      ).not.toBeInTheDocument();
    });
  });

  it('says so when the host does not exist', async () => {
    server.use(
      http.get(`/api/v1/hosts/${HOST_ID}`, () =>
        problemResponse({ type: 'about:blank', title: 'Not Found', status: 404 }),
      ),
    );

    renderRoute(`/hosts/${HOST_ID}`);

    expect(await screen.findByRole('heading', { name: 'Host not found' })).toBeInTheDocument();
  });
});
