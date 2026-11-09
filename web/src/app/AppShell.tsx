import { Activity, LayoutDashboard, ListChecks, Server, type LucideIcon } from 'lucide-react';
import { useEffect, useRef } from 'react';
import { Link, NavLink, Outlet, ScrollRestoration, useLocation, useNavigation } from 'react-router';
import { Logo } from '../components/Logo';
import { cx } from '../lib/cx';
import { ThemeToggle } from './ThemeToggle';

const NAV_ITEMS: readonly { to: string; label: string; icon: LucideIcon; end?: boolean }[] = [
  { to: '/', label: 'Overview', icon: LayoutDashboard, end: true },
  { to: '/hosts', label: 'Hosts', icon: Server },
  { to: '/checks', label: 'Checks', icon: ListChecks },
  { to: '/events', label: 'Events', icon: Activity },
];

export function AppShell() {
  const { pathname } = useLocation();
  const navigation = useNavigation();
  const mainRef = useRef<HTMLElement>(null);
  const previousPath = useRef(pathname);

  // Move focus to the new page on route changes (not on query-string updates), so
  // keyboard and screen-reader users start at the content rather than the old link.
  useEffect(() => {
    if (previousPath.current === pathname) return;
    previousPath.current = pathname;
    mainRef.current?.focus({ preventScroll: true });
  }, [pathname]);

  return (
    <div className="flex min-h-dvh flex-col">
      <a
        href="#main"
        className="sr-only z-50 rounded-md bg-surface px-3 py-2 text-ink shadow-md focus:not-sr-only focus:fixed focus:top-2 focus:left-2"
      >
        Skip to content
      </a>
      <header className="sticky top-0 z-40 border-b border-border bg-surface/95 backdrop-blur-sm">
        <div className="mx-auto flex max-w-7xl flex-wrap items-center gap-x-6 gap-y-2 px-4 py-2.5">
          <Link to="/" className="flex items-center gap-2 rounded-md font-semibold text-ink">
            <Logo className="size-7" />
            <span className="text-base tracking-tight">Driftwatch</span>
          </Link>
          <nav aria-label="Primary" className="order-last w-full sm:order-none sm:w-auto">
            <ul className="flex gap-0.5 sm:-mx-1">
              {NAV_ITEMS.map(({ to, label, icon: Icon, end }) => (
                <li key={to} className="min-w-0 flex-1 sm:flex-none">
                  <NavLink
                    to={to}
                    end={end}
                    className={({ isActive }) =>
                      cx(
                        'flex flex-col items-center justify-center gap-0.5 rounded-md px-1 py-1 text-xs font-medium transition-colors sm:flex-row sm:gap-1.5 sm:px-2.5 sm:py-1.5 sm:text-sm',
                        isActive
                          ? 'bg-accent-soft text-ink'
                          : 'text-ink-secondary hover:bg-sunken hover:text-ink',
                      )
                    }
                  >
                    <Icon aria-hidden className="size-4 shrink-0" />
                    {label}
                  </NavLink>
                </li>
              ))}
            </ul>
          </nav>
          <div className="ml-auto">
            <ThemeToggle />
          </div>
        </div>
        {navigation.state === 'loading' ? (
          <div
            role="progressbar"
            aria-label="Loading page"
            className="absolute inset-x-0 -bottom-px h-0.5 bg-accent motion-safe:animate-pulse"
          />
        ) : null}
      </header>
      <main
        id="main"
        ref={mainRef}
        tabIndex={-1}
        className="mx-auto w-full max-w-7xl flex-1 px-4 py-6 outline-none"
      >
        <Outlet />
      </main>
      <ScrollRestoration />
    </div>
  );
}
