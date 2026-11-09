package io.github.tahausama210.driftwatch.ingest;

import io.github.tahausama210.driftwatch.checks.CheckStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import org.jspecify.annotations.Nullable;

/**
 * The compliance score: {@code 100 × Σ weight(PASS) / Σ weight(PASS ∪ FAIL ∪ ERROR)}, rounded half-up
 * to one decimal place.
 *
 * <p>{@code NOT_APPLICABLE} results are excluded and {@code ERROR} counts as failing. The division
 * is done on {@link BigDecimal} so the rounding is applied to the exact quotient rather than to a
 * binary approximation of it: 100 × 1/16 = 6.25 becomes 6.3, where half-even rounding would give 6.2.
 */
final class ComplianceScore {

    private ComplianceScore() {}

    /** Null when no result is applicable, i.e. there is nothing to score. */
    static @Nullable BigDecimal of(Collection<CheckResultReport> results) {
        long passingWeight = 0;
        long applicableWeight = 0;
        for (CheckResultReport result : results) {
            if (result.status() == CheckStatus.NOT_APPLICABLE) {
                continue;
            }
            applicableWeight += result.severity().weight();
            if (result.status() == CheckStatus.PASS) {
                passingWeight += result.severity().weight();
            }
        }
        if (applicableWeight == 0) {
            return null;
        }
        return BigDecimal.valueOf(100 * passingWeight)
                .divide(BigDecimal.valueOf(applicableWeight), 1, RoundingMode.HALF_UP);
    }
}
