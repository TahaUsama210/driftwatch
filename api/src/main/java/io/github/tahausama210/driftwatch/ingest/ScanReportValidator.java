package io.github.tahausama210.driftwatch.ingest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

/**
 * Cross-field rules for {@link ScanReport}. Registered on the controller's data binder, so its
 * errors are reported together with the Bean Validation errors in a single {@code 400}. Fields that
 * are missing are skipped here; Bean Validation reports them.
 */
@Component
class ScanReportValidator implements Validator {

    /** How far an agent's clock may run ahead of the server's. */
    static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    private final Clock clock;

    ScanReportValidator(Clock clock) {
        this.clock = clock;
    }

    @Override
    public boolean supports(Class<?> type) {
        return ScanReport.class.isAssignableFrom(type);
    }

    @Override
    public void validate(Object target, Errors errors) {
        ScanReport report = (ScanReport) target;
        validateTimestamps(report.startedAt(), report.finishedAt(), errors);
        validateUniqueCheckIds(report.results(), errors);
    }

    private void validateTimestamps(Instant startedAt, Instant finishedAt, Errors errors) {
        if (startedAt != null && finishedAt != null && startedAt.isAfter(finishedAt)) {
            errors.rejectValue("startedAt", "scan.startedAfterFinished", "must not be after finishedAt");
        }
        if (finishedAt != null && finishedAt.isAfter(clock.instant().plus(MAX_CLOCK_SKEW))) {
            errors.rejectValue("finishedAt", "scan.finishedInFuture", "must not be more than 5 minutes in the future");
        }
    }

    private static void validateUniqueCheckIds(List<CheckResultReport> results, Errors errors) {
        if (results == null) {
            return;
        }
        Map<String, Integer> firstIndexById = new HashMap<>();
        for (int i = 0; i < results.size(); i++) {
            CheckResultReport result = results.get(i);
            if (result == null || result.checkId() == null) {
                continue;
            }
            Integer firstIndex = firstIndexById.putIfAbsent(result.checkId(), i);
            if (firstIndex != null) {
                errors.rejectValue(
                        "results[" + i + "].checkId",
                        "scan.duplicateCheckId",
                        "duplicates results[" + firstIndex + "].checkId");
            }
        }
    }
}
