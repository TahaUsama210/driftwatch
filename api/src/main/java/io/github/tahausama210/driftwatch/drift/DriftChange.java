package io.github.tahausama210.driftwatch.drift;

import io.github.tahausama210.driftwatch.checks.Category;
import io.github.tahausama210.driftwatch.checks.CheckStatus;
import io.github.tahausama210.driftwatch.checks.Severity;
import java.util.Comparator;
import org.jspecify.annotations.Nullable;

/** A check whose status differs between two scans of the same host. */
public record DriftChange(
        String checkId,
        String title,
        Category category,
        Severity severity,
        CheckStatus fromStatus,
        CheckStatus toStatus,
        @Nullable String fromEvidence,
        @Nullable String toEvidence,
        DriftDirection direction) {

    /** Regressions first, then resolutions, then other changes; heavier severities first within each. */
    public static final Comparator<DriftChange> REPORT_ORDER = Comparator.comparing(DriftChange::direction)
            .thenComparing(change -> change.severity().weight(), Comparator.reverseOrder())
            .thenComparing(DriftChange::checkId);
}
