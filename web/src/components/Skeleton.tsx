import type { ReactNode } from 'react';
import { cx } from '../lib/cx';

export function Skeleton({ className }: { className?: string }) {
  return (
    <div aria-hidden className={cx('rounded bg-border/70 motion-safe:animate-pulse', className)} />
  );
}

/** Wraps placeholder shapes so screen readers hear a single "Loading …" message. */
export function LoadingRegion({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div role="status" aria-busy="true">
      <span className="sr-only">{label}</span>
      {children}
    </div>
  );
}

export function SkeletonRows({ rows = 5, label }: { rows?: number; label: string }) {
  return (
    <LoadingRegion label={label}>
      <div className="space-y-3 p-4">
        {Array.from({ length: rows }, (_, index) => (
          <Skeleton key={index} className="h-6 w-full" />
        ))}
      </div>
    </LoadingRegion>
  );
}
