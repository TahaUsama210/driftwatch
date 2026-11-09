import type { DriftChange } from '../../../api/types';
import { DRIFT_DIRECTIONS } from '../../../domain/drift';
import { SEVERITIES } from '../../../domain/severity';

/** Regressions first, then resolutions, then other changes; heavier checks first within each. */
export function sortChanges(changes: readonly DriftChange[]): DriftChange[] {
  return [...changes].sort(
    (a, b) =>
      DRIFT_DIRECTIONS[a.direction].rank - DRIFT_DIRECTIONS[b.direction].rank ||
      SEVERITIES[b.severity].weight - SEVERITIES[a.severity].weight ||
      a.checkId.localeCompare(b.checkId),
  );
}
