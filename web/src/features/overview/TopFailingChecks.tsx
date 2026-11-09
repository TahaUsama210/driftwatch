import { ShieldCheck } from 'lucide-react';
import { Link } from 'react-router';
import type { CheckFailure } from '../../api/types';
import { SeverityBadge } from '../../components/badges/SeverityBadge';
import { Card } from '../../components/Card';
import { EmptyState } from '../../components/EmptyState';
import { CATEGORY_LABELS } from '../../domain/category';
import { formatCount } from '../../lib/format';

interface TopFailingChecksProps {
  checks: readonly CheckFailure[];
  hostCount: number;
}

export function TopFailingChecks({ checks, hostCount }: TopFailingChecksProps) {
  return (
    <Card
      title="Top failing checks"
      description="By hosts failing, then severity"
      actions={
        <Link to="/checks" className="text-xs font-medium text-accent hover:underline">
          All checks
        </Link>
      }
      flush
    >
      {checks.length === 0 ? (
        <EmptyState icon={ShieldCheck} title="No failing checks">
          Every host passes every applicable check.
        </EmptyState>
      ) : (
        <ol className="divide-y divide-border">
          {checks.map((check) => {
            const share = hostCount > 0 ? Math.min(1, check.failingHosts / hostCount) : 0;
            return (
              <li key={check.checkId} className="space-y-2 px-4 py-3">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <Link
                      to={`/checks/${check.checkId}`}
                      className="font-medium break-words text-ink hover:underline"
                    >
                      {check.title}
                    </Link>
                    <p className="mt-0.5 flex flex-wrap items-center gap-x-2 text-xs text-ink-muted">
                      <span>{CATEGORY_LABELS[check.category]}</span>
                      <SeverityBadge severity={check.severity} />
                    </p>
                  </div>
                  <p className="text-right text-xs whitespace-nowrap text-ink-secondary tabular-nums">
                    <span className="text-sm font-semibold text-ink">
                      {formatCount(check.failingHosts)}
                    </span>{' '}
                    of {formatCount(hostCount)} hosts
                  </p>
                </div>
                <div aria-hidden className="h-1.5 overflow-hidden rounded-full bg-critical-soft">
                  <div
                    className="h-full rounded-full bg-critical"
                    style={{ width: `${(share * 100).toFixed(1)}%` }}
                  />
                </div>
              </li>
            );
          })}
        </ol>
      )}
    </Card>
  );
}
