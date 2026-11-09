import type { CheckHostStatus } from '../../api/types';
import { isFailing } from '../../domain/checkStatus';

/**
 * FAIL and ERROR first, then everything else, each group by hostname. The contract
 * promises this order; sorting again here keeps the page right if a server does not.
 */
export function sortFailuresFirst(hosts: readonly CheckHostStatus[]): CheckHostStatus[] {
  return [...hosts].sort(
    (a, b) =>
      Number(isFailing(b.status)) - Number(isFailing(a.status)) ||
      a.hostname.localeCompare(b.hostname),
  );
}
