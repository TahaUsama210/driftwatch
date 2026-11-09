import { useId, type ReactNode } from 'react';
import { cx } from '../lib/cx';

interface CardProps {
  title: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  children: ReactNode;
  className?: string;
  /** Remove body padding, e.g. for edge-to-edge tables. */
  flush?: boolean;
}

/** The untitled surface a Card is built on, for content that brings its own header. */
export function Panel({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <div className={cx('min-w-0 rounded-lg border border-border bg-surface', className)}>
      {children}
    </div>
  );
}

/** A titled section of a page. The heading labels the region for assistive tech. */
export function Card({ title, description, actions, children, className, flush }: CardProps) {
  const titleId = useId();
  return (
    <section
      aria-labelledby={titleId}
      className={cx('flex min-w-0 flex-col rounded-lg border border-border bg-surface', className)}
    >
      <header className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2 border-b border-border px-4 py-3">
        <div className="min-w-0">
          <h2 id={titleId} className="text-sm font-semibold text-ink">
            {title}
          </h2>
          {description ? <p className="mt-0.5 text-xs text-ink-muted">{description}</p> : null}
        </div>
        {actions ? <div className="flex flex-wrap items-center gap-2">{actions}</div> : null}
      </header>
      <div className={cx('flex-1', !flush && 'p-4')}>{children}</div>
    </section>
  );
}
