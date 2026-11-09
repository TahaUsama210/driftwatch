package io.github.tahausama210.driftwatch.drift;

import io.github.tahausama210.driftwatch.checks.CheckStatus;
import java.util.Optional;

/** How a check's status moved between two scans. Declaration order is the report order. */
public enum DriftDirection {
    /** {@code PASS} or {@code NOT_APPLICABLE} became {@code FAIL} or {@code ERROR}. */
    REGRESSED,
    /** {@code FAIL} or {@code ERROR} became {@code PASS}. */
    RESOLVED,
    /** Any other status change, e.g. {@code FAIL → ERROR} or {@code PASS → NOT_APPLICABLE}. */
    CHANGED;

    /** Empty when the status did not change: a different evidence string alone is not drift. */
    public static Optional<DriftDirection> between(CheckStatus from, CheckStatus to) {
        if (from == to) {
            return Optional.empty();
        }
        if (!from.isFailing() && to.isFailing()) {
            return Optional.of(REGRESSED);
        }
        if (from.isFailing() && to == CheckStatus.PASS) {
            return Optional.of(RESOLVED);
        }
        return Optional.of(CHANGED);
    }
}
