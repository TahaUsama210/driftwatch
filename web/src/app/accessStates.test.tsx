import { screen } from '@testing-library/react';
import { http } from 'msw';
import { describe, expect, it, vi } from 'vitest';
import { MockAuth } from '../auth/mockAuth';
import { problemResponse } from '../mocks/handlers';
import { renderRoute } from '../test/render';
import { server } from '../test/server';

describe('access states', () => {
  it('shows "insufficient permissions" when the API answers 403', async () => {
    server.use(
      http.get('/api/v1/hosts', () =>
        problemResponse({
          type: 'about:blank',
          title: 'Forbidden',
          status: 403,
          detail: 'This operation requires the viewer role.',
        }),
      ),
    );
    renderRoute('/hosts', { role: 'viewer' });

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Insufficient permissions');
    expect(alert).toHaveTextContent('This operation requires the viewer role.');
    expect(alert).toHaveTextContent('Your role: Viewer.');
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
  });

  it('re-authenticates on 401, and offers an explicit sign-in when that is suppressed', async () => {
    const auth = new MockAuth('viewer');
    const reauthenticate = vi.spyOn(auth, 'reauthenticate').mockImplementation(() => undefined);
    const signIn = vi.spyOn(auth, 'signIn');
    server.use(
      http.get('/api/v1/checks', () =>
        problemResponse({ type: 'about:blank', title: 'Unauthorized', status: 401 }),
      ),
    );
    const { user } = renderRoute('/checks', { auth });

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Your session is no longer valid');
    expect(reauthenticate).toHaveBeenCalled();

    await user.click(screen.getByRole('button', { name: 'Sign in again' }));
    expect(signIn).toHaveBeenCalledTimes(1);
  });
});
