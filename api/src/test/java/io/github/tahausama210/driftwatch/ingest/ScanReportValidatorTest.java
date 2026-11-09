package io.github.tahausama210.driftwatch.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import io.github.tahausama210.driftwatch.checks.Category;
import io.github.tahausama210.driftwatch.checks.CheckStatus;
import io.github.tahausama210.driftwatch.checks.Severity;
import io.github.tahausama210.driftwatch.hosts.HostFacts;
import io.github.tahausama210.driftwatch.hosts.OsInfo;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.Errors;
import org.springframework.validation.FieldError;

class ScanReportValidatorTest {

    private static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");

    private final ScanReportValidator validator = new ScanReportValidator(Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aConsistentReportHasNoErrors() {
        assertThat(validate(report(NOW.minusSeconds(5), NOW, "ssh.a", "ssh.b")).hasErrors())
                .isFalse();
    }

    @Test
    void startedAtEqualToFinishedAtIsAllowed() {
        assertThat(validate(report(NOW, NOW, "ssh.a")).hasErrors()).isFalse();
    }

    @Test
    void startedAfterFinishedIsRejectedOnStartedAt() {
        assertThat(fields(validate(report(NOW.plusSeconds(1), NOW, "ssh.a")))).containsExactly("startedAt");
    }

    @Test
    void clockSkewOfExactlyFiveMinutesIsTolerated() {
        Instant limit = NOW.plus(ScanReportValidator.MAX_CLOCK_SKEW);

        assertThat(validate(report(limit, limit, "ssh.a")).hasErrors()).isFalse();
        assertThat(fields(validate(report(limit, limit.plusMillis(1), "ssh.a"))))
                .containsExactly("finishedAt");
    }

    @Test
    void everyRepeatedCheckIdIsReportedAgainstItsFirstOccurrence() {
        Errors errors = validate(report(NOW, NOW, "ssh.a", "ssh.b", "ssh.a", "ssh.b", "ssh.a"));

        assertThat(errors.getFieldErrors())
                .extracting(FieldError::getField, FieldError::getDefaultMessage)
                .containsExactly(
                        tuple("results[2].checkId", "duplicates results[0].checkId"),
                        tuple("results[3].checkId", "duplicates results[1].checkId"),
                        tuple("results[4].checkId", "duplicates results[0].checkId"));
    }

    @Test
    void missingFieldsAreLeftToBeanValidation() {
        ScanReport incomplete = new ScanReport(UUID.randomUUID(), "0.1.0", null, null, null, null);

        assertThat(validate(incomplete).hasErrors()).isFalse();
    }

    private Errors validate(ScanReport report) {
        Errors errors = new BeanPropertyBindingResult(report, "scanReport");
        validator.validate(report, errors);
        return errors;
    }

    private static List<String> fields(Errors errors) {
        return errors.getFieldErrors().stream().map(FieldError::getField).toList();
    }

    private static ScanReport report(Instant startedAt, Instant finishedAt, String... checkIds) {
        List<CheckResultReport> results = Arrays.stream(checkIds)
                .map(id ->
                        new CheckResultReport(id, "t", Category.SSH, Severity.LOW, CheckStatus.PASS, null, null, null))
                .toList();
        return new ScanReport(
                UUID.randomUUID(),
                "0.1.0",
                startedAt,
                finishedAt,
                new HostFacts("web-01", new OsInfo("ubuntu", "24.04", "Ubuntu"), "6.8", "amd64"),
                results);
    }
}
