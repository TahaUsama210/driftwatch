package io.github.tahausama210.driftwatch.drift;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tahausama210.driftwatch.checks.CheckStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

class DriftDirectionTest {

    /** Every ordered pair of distinct statuses, with the direction from docs/CONTRACTS.md. */
    @ParameterizedTest(name = "{0} -> {1} is {2}")
    @CsvSource({
        "PASS,           FAIL,           REGRESSED",
        "PASS,           ERROR,          REGRESSED",
        "NOT_APPLICABLE, FAIL,           REGRESSED",
        "NOT_APPLICABLE, ERROR,          REGRESSED",
        "FAIL,           PASS,           RESOLVED",
        "ERROR,          PASS,           RESOLVED",
        "FAIL,           ERROR,          CHANGED",
        "ERROR,          FAIL,           CHANGED",
        "PASS,           NOT_APPLICABLE, CHANGED",
        "NOT_APPLICABLE, PASS,           CHANGED",
        "FAIL,           NOT_APPLICABLE, CHANGED",
        "ERROR,          NOT_APPLICABLE, CHANGED"
    })
    void statusChangesMapToTheirDirection(CheckStatus from, CheckStatus to, DriftDirection expected) {
        assertThat(DriftDirection.between(from, to)).contains(expected);
    }

    @ParameterizedTest
    @EnumSource(CheckStatus.class)
    void anUnchangedStatusIsNotDrift(CheckStatus status) {
        assertThat(DriftDirection.between(status, status)).isEmpty();
    }
}
