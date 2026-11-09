package io.github.tahausama210.driftwatch.checks;

import java.util.Comparator;

/**
 * A catalog check with its status across every host's latest scan.
 *
 * @param counts how many hosts' latest scans report each status for this check
 */
public record CheckSummary(String id, String title, Category category, Severity severity, StatusCounts counts) {

    /** Most failing hosts first, then the heavier severity, then id: roughly "what to fix first". */
    public static final Comparator<CheckSummary> BY_FLEET_IMPACT = Comparator.comparing(
                    (CheckSummary check) -> check.counts().failing(), Comparator.reverseOrder())
            .thenComparing(check -> check.severity().weight(), Comparator.reverseOrder())
            .thenComparing(CheckSummary::id);
}
