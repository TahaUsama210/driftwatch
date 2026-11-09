package io.github.tahausama210.driftwatch.ingest;

import io.github.tahausama210.driftwatch.checks.Category;
import io.github.tahausama210.driftwatch.checks.CheckDefinition;
import io.github.tahausama210.driftwatch.checks.CheckStatus;
import io.github.tahausama210.driftwatch.checks.Severity;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/** One check result in a submitted scan report. */
public record CheckResultReport(
        @NotNull @Pattern(regexp = "^[a-z0-9_.]{3,64}$") String checkId,
        @NotNull @Size(max = 200) String title,
        @NotNull Category category,
        @NotNull Severity severity,
        @NotNull CheckStatus status,
        @Size(max = 200) @Nullable String expected,
        @Size(max = 200) @Nullable String actual,
        @Size(max = 1000) @Nullable String evidence) {

    CheckDefinition definition() {
        return new CheckDefinition(checkId, title, category, severity);
    }
}
