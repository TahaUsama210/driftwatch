import { screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { renderRoute } from '../test/render';

describe('routing', () => {
  it('renders a not-found page inside the app shell for unknown paths', async () => {
    renderRoute('/no-such-page');

    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeInTheDocument();
    expect(screen.getByText('/no-such-page')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Go to overview' })).toHaveAttribute('href', '/');
    expect(screen.getByRole('navigation', { name: 'Primary' })).toBeInTheDocument();
  });

  it('marks the current section in the primary navigation', async () => {
    renderRoute('/checks');

    expect(await screen.findByRole('heading', { name: 'Checks' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Checks' })).toHaveAttribute('aria-current', 'page');
    expect(screen.getByRole('link', { name: 'Overview' })).not.toHaveAttribute('aria-current');
  });
});
