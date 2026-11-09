import { ListChecks } from 'lucide-react';
import { Link, useSearchParams } from 'react-router';
import type { Category, CheckSummary } from '../../api/types';
import { SeverityBadge } from '../../components/badges/SeverityBadge';
import { Panel } from '../../components/Card';
import { StatusBar, StatusLegend } from '../../components/charts/StatusBar';
import { EmptyState } from '../../components/EmptyState';
import { PageHeader } from '../../components/PageHeader';
import { QueryState } from '../../components/QueryState';
import { SegmentedControl, type SegmentOption } from '../../components/SegmentedControl';
import { SkeletonRows } from '../../components/Skeleton';
import { CATEGORY_LABELS, CATEGORY_ORDER, isCategory } from '../../domain/category';
import { formatCount } from '../../lib/format';
import { useChecks } from './api';

type CategoryFilter = Category | 'all';

const CATEGORY_OPTIONS: readonly SegmentOption<CategoryFilter>[] = [
  { value: 'all', label: 'All' },
  ...CATEGORY_ORDER.map((category) => ({ value: category, label: CATEGORY_LABELS[category] })),
];

const TH = 'px-4 py-2.5 text-left text-xs font-medium whitespace-nowrap text-ink-secondary';

export function ChecksPage() {
  const checks = useChecks();
  const [searchParams, setSearchParams] = useSearchParams();
  const rawCategory = searchParams.get('category');
  const category: CategoryFilter = isCategory(rawCategory) ? rawCategory : 'all';

  return (
    <>
      <PageHeader
        title="Checks"
        description="The hardening checks every agent runs, with each host's latest status."
      />
      <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
        <SegmentedControl
          label="Category"
          options={CATEGORY_OPTIONS}
          value={category}
          onChange={(value) => {
            setSearchParams(value === 'all' ? {} : { category: value }, { replace: true });
          }}
        />
        <StatusLegend />
      </div>
      <Panel>
        <QueryState query={checks} loading={<SkeletonRows rows={10} label="Loading checks" />}>
          {(items) => {
            const visible =
              category === 'all' ? items : items.filter((c) => c.category === category);
            return visible.length === 0 ? (
              <EmptyState icon={ListChecks} title="No checks in this category" />
            ) : (
              <ChecksTable checks={visible} />
            );
          }}
        </QueryState>
      </Panel>
    </>
  );
}

function ChecksTable({ checks }: { checks: readonly CheckSummary[] }) {
  return (
    <div className="relative overflow-x-auto">
      <table className="w-full min-w-[46rem] border-collapse">
        <caption className="sr-only">
          Check catalog, most failing hosts first, with status across each host&apos;s latest scan
        </caption>
        <thead className="bg-sunken">
          <tr>
            <th scope="col" className={TH}>
              Check
            </th>
            <th scope="col" className={TH}>
              Category
            </th>
            <th scope="col" className={TH}>
              Severity
            </th>
            <th scope="col" className={`${TH} w-[34%]`}>
              Fleet status
            </th>
          </tr>
        </thead>
        <tbody className="divide-y divide-border">
          {checks.map((check) => {
            const failing = check.counts.fail + check.counts.error;
            return (
              <tr key={check.id} className="hover:bg-sunken/60">
                <td className="px-4 py-2.5">
                  <Link
                    to={`/checks/${check.id}`}
                    className="font-medium text-ink hover:text-accent hover:underline"
                  >
                    {check.title}
                  </Link>
                  <span className="block font-mono text-xs text-ink-muted">{check.id}</span>
                </td>
                <td className="px-4 py-2.5 whitespace-nowrap text-ink-secondary">
                  {CATEGORY_LABELS[check.category]}
                </td>
                <td className="px-4 py-2.5">
                  <SeverityBadge severity={check.severity} />
                </td>
                <td className="px-4 py-2.5">
                  <div className="flex items-center gap-3">
                    <StatusBar counts={check.counts} className="flex-1" />
                    <span className="w-20 shrink-0 text-right text-xs whitespace-nowrap text-ink-secondary tabular-nums">
                      {failing > 0 ? (
                        <>
                          <span className="font-semibold text-ink">{formatCount(failing)}</span>{' '}
                          failing
                        </>
                      ) : (
                        'none failing'
                      )}
                    </span>
                  </div>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
