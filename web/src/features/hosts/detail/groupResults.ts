import type { Category, CheckResult, CheckStatus } from '../../../api/types';
import { CATEGORY_ORDER } from '../../../domain/category';
import { CHECK_STATUSES } from '../../../domain/checkStatus';

export type StatusFilter = CheckStatus | 'ALL';

/** Failing first within a category, then by check ID, so problems lead each group. */
export function groupResults(
  results: readonly CheckResult[],
  filter: StatusFilter,
): { category: Category; results: CheckResult[] }[] {
  const visible = filter === 'ALL' ? results : results.filter((result) => result.status === filter);
  return CATEGORY_ORDER.map((category) => ({
    category,
    results: visible
      .filter((result) => result.category === category)
      .sort(
        (a, b) =>
          CHECK_STATUSES[a.status].rank - CHECK_STATUSES[b.status].rank ||
          a.checkId.localeCompare(b.checkId),
      ),
  })).filter((group) => group.results.length > 0);
}
