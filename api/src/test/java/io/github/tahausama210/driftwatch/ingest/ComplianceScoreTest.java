package io.github.tahausama210.driftwatch.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.tahausama210.driftwatch.checks.Category;
import io.github.tahausama210.driftwatch.checks.CheckStatus;
import io.github.tahausama210.driftwatch.checks.Severity;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ComplianceScoreTest {

    /**
     * Each row lists results as {@code severity:status} pairs, with the working shown.
     * Weights: low 1, medium 2, high 3, critical 5.
     */
    @ParameterizedTest(name = "[{index}] {0} -> {1}")
    @CsvSource(delimiter = '|', textBlock = """
            # results                                              | score | why
            high:PASS critical:PASS low:PASS                       | 100.0 | 9/9
            high:FAIL critical:FAIL                                |   0.0 | 0/8
            high:PASS high:ERROR                                   |  50.0 | ERROR counts as failing: 3/6
            low:PASS critical:NOT_APPLICABLE                       | 100.0 | NOT_APPLICABLE is excluded: 1/1
            high:PASS critical:PASS medium:FAIL low:NOT_APPLICABLE |  80.0 | 8/10
            high:PASS critical:PASS medium:FAIL high:ERROR         |  61.5 | 8/13 = 61.538
            high:PASS critical:FAIL medium:FAIL                    |  30.0 | 3/10
            critical:PASS medium:PASS low:FAIL                     |  87.5 | 7/8
            low:PASS critical:FAIL critical:FAIL critical:FAIL     |   6.3 | 1/16 = 6.25 rounds half-up (half-even gives 6.2)
            critical:PASS critical:FAIL critical:FAIL low:FAIL     |  31.3 | 5/16 = 31.25 rounds half-up (half-even gives 31.2)
            high:PASS high:PASS medium:FAIL                        |  75.0 | 6/8
            low:PASS low:FAIL low:FAIL                             |  33.3 | 1/3 = 33.333
            low:PASS low:PASS low:FAIL                             |  66.7 | 2/3 = 66.667
            """)
    void scoreIsTheWeightedPassShareRoundedHalfUpToOneDecimal(String results, BigDecimal expected, String why) {
        assertThat(ComplianceScore.of(parse(results))).as(why).isEqualByComparingTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"low:NOT_APPLICABLE", "critical:NOT_APPLICABLE high:NOT_APPLICABLE"})
    void scoreIsNullWhenNothingIsApplicable(String results) {
        assertThat(ComplianceScore.of(parse(results))).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"high:PASS high:PASS high:FAIL", "low:PASS", "low:PASS low:PASS low:FAIL"})
    void scoreAlwaysHasExactlyOneDecimalPlace(String results) {
        assertThat(ComplianceScore.of(parse(results)).scale()).isOne();
    }

    private static List<CheckResultReport> parse(String results) {
        List<CheckResultReport> parsed = new ArrayList<>();
        for (String token : results.trim().split("\\s+")) {
            String[] parts = token.split(":");
            parsed.add(new CheckResultReport(
                    "ssh.check_" + parsed.size(),
                    "A check",
                    Category.SSH,
                    Severity.fromWireValue(parts[0]),
                    CheckStatus.valueOf(parts[1]),
                    null,
                    null,
                    null));
        }
        return parsed;
    }
}
