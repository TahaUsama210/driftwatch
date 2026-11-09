package io.github.tahausama210.driftwatch.fleet;

import io.github.tahausama210.driftwatch.checks.Category;
import io.github.tahausama210.driftwatch.checks.CheckSummary;
import io.github.tahausama210.driftwatch.checks.Severity;

/** A check failing somewhere in the fleet; {@code failingHosts} counts FAIL or ERROR on latest scans. */
public record CheckFailure(String checkId, String title, Category category, Severity severity, int failingHosts) {

    static CheckFailure of(CheckSummary check) {
        return new CheckFailure(
                check.id(),
                check.title(),
                check.category(),
                check.severity(),
                check.counts().failing());
    }
}
