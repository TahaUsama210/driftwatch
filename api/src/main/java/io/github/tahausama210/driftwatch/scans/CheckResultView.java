package io.github.tahausama210.driftwatch.scans;

import io.github.tahausama210.driftwatch.checks.Category;
import io.github.tahausama210.driftwatch.checks.CheckStatus;
import io.github.tahausama210.driftwatch.checks.Severity;
import org.jspecify.annotations.Nullable;

/** One check result of a stored scan, with the check's catalog metadata. */
public record CheckResultView(
        String checkId,
        String title,
        Category category,
        Severity severity,
        CheckStatus status,
        @Nullable String expected,
        @Nullable String actual,
        @Nullable String evidence) {}
