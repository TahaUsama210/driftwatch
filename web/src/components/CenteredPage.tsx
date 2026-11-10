import type { ReactNode } from 'react';
import { ThemeToggle } from '../app/ThemeToggle';
import { Logo } from './Logo';
import { PageTitle } from './PageHeader';

interface CenteredPageProps {
  title: string;
  children: ReactNode;
  /** Theme toggle in the corner; off for screens shown before the app is usable. */
  showThemeToggle?: boolean;
}

/** The full-page layout for screens outside the app shell: sign-in, no access, startup errors. */
export function CenteredPage({ title, children, showThemeToggle = true }: CenteredPageProps) {
  return (
    <div className="flex min-h-dvh flex-col">
      <PageTitle title={title} />
      {showThemeToggle ? (
        <div className="flex justify-end px-4 py-3">
          <ThemeToggle />
        </div>
      ) : null}
      <main className="flex flex-1 items-start justify-center px-4 pt-[12vh] pb-12 sm:items-center sm:pt-0">
        <div className="w-full max-w-sm rounded-lg border border-border bg-surface p-6 shadow-xs">
          <div className="mb-5 flex items-center gap-2 font-semibold text-ink">
            <Logo className="size-8" />
            <span className="text-base tracking-tight">Driftwatch</span>
          </div>
          {children}
        </div>
      </main>
    </div>
  );
}
