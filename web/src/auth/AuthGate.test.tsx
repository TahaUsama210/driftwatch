import { screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { renderRoute } from '../test/render';
import { MockAuth, mockRoleFromSearch } from './mockAuth';
import { mockAccessToken, rolesFromMockToken } from './mockToken';
import { hasRole, roleLabel, rolesFromAccessToken } from './roles';

describe('signed out', () => {
  it('shows a landing with a Sign in button and never starts sign-in by itself', async () => {
    const auth = new MockAuth('viewer', { signedIn: false });
    const signIn = vi.spyOn(auth, 'signIn');
    const { user } = renderRoute('/hosts', { auth });

    expect(screen.getByRole('heading', { name: 'Sign in to Driftwatch' })).toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Primary' })).not.toBeInTheDocument();
    expect(signIn).not.toHaveBeenCalled();

    await user.click(screen.getByRole('button', { name: 'Sign in' }));

    expect(signIn).toHaveBeenCalledTimes(1);
    expect(await screen.findByRole('heading', { name: 'Hosts' })).toBeInTheDocument();
  });

  it('shows the reason it is signed out', () => {
    const auth = new MockAuth();
    vi.spyOn(auth, 'getState').mockReturnValue({
      status: 'signed-out',
      notice: { title: 'Sign-in did not complete', detail: 'User cancelled' },
    });
    renderRoute('/', { auth });

    expect(screen.getByRole('alert')).toHaveTextContent('Sign-in did not completeUser cancelled');
  });

  it('shows a splash while the session is being resolved', () => {
    const auth = new MockAuth();
    vi.spyOn(auth, 'getState').mockReturnValue({ status: 'loading' });
    renderRoute('/', { auth });

    expect(screen.getByRole('status')).toHaveTextContent('Checking your session');
  });
});

describe('signed in', () => {
  it('shows the user menu with name and role, and signs out to the landing', async () => {
    const { user } = renderRoute('/checks', { role: 'viewer' });
    await screen.findByRole('heading', { name: 'Checks' });

    const toggle = screen.getByRole('button', { name: /Demo Viewer, Viewer\. Account/ });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await user.click(toggle);
    expect(toggle).toHaveAttribute('aria-expanded', 'true');

    const panel = document.getElementById(toggle.getAttribute('aria-controls') ?? '');
    if (!panel) throw new Error('the toggle controls no panel');
    expect(panel).toBeVisible();
    expect(panel).toHaveTextContent('viewer');
    expect(panel).toHaveTextContent('Role: Viewer');

    await user.keyboard('{Escape}');
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(toggle).toHaveFocus();

    await user.click(toggle);
    await user.click(within(panel).getByRole('button', { name: 'Sign out' }));

    expect(screen.getByRole('heading', { name: 'Sign in to Driftwatch' })).toBeInTheDocument();
  });

  it('switches the demo role from the menu in mock mode', async () => {
    const { user } = renderRoute('/', { role: 'viewer' });
    await user.click(await screen.findByRole('button', { name: /Account/ }));

    await user.click(screen.getByRole('radio', { name: 'Admin' }));

    expect(
      await screen.findByRole('button', { name: /Demo Admin, Admin\. Account/ }),
    ).toBeInTheDocument();
  });

  it('turns away an account without a Driftwatch role', async () => {
    const { user } = renderRoute('/', { role: 'none' });

    expect(screen.getByRole('heading', { name: 'Insufficient permissions' })).toBeInTheDocument();
    expect(screen.queryByRole('navigation', { name: 'Primary' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Sign out' }));
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeInTheDocument();
  });
});

describe('roles', () => {
  it('treats admin as including viewer', () => {
    const admin = { id: 'a', username: 'a', displayName: 'A', roles: ['admin'] as const };
    expect(hasRole(admin, 'viewer')).toBe(true);
    expect(roleLabel(admin)).toBe('Admin');
    expect(roleLabel({ ...admin, roles: [] })).toBe('No access');
  });

  it.each(['not-a-jwt', 'a.!!!.c', 'a.bnVsbA.c', 'a.eyJyZWFsbV9hY2Nlc3MiOjF9.c'])(
    'finds no roles in %s',
    (token) => {
      expect(rolesFromAccessToken(token)).toEqual([]);
    },
  );

  it('round-trips mock tokens and rejects anything else', () => {
    expect(rolesFromMockToken(mockAccessToken(['admin', 'viewer']))).toEqual(['admin', 'viewer']);
    expect(rolesFromMockToken(mockAccessToken([]))).toEqual([]);
    expect(rolesFromMockToken('mock.root')).toBeUndefined();
    expect(rolesFromMockToken('eyJ.real.jwt')).toBeUndefined();
  });

  it('reads the mock role from the URL, defaulting to admin', () => {
    expect(mockRoleFromSearch('?role=viewer')).toBe('viewer');
    expect(mockRoleFromSearch('?role=root')).toBe('admin');
  });
});
