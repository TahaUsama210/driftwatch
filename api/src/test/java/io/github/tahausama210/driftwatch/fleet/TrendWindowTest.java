package io.github.tahausama210.driftwatch.fleet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.tahausama210.driftwatch.common.error.InvalidRequestException;
import java.time.Instant;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TrendWindowTest {

    @ParameterizedTest(name = "now={0} hours={1} bucketMinutes={2}")
    @CsvSource({
        // now,                 hours, minutes, first bucket,           last bucket
        "2026-11-03T12:34:56Z,  24,    60,      2026-11-02T13:00:00Z,   2026-11-03T12:00:00Z",
        "2026-11-03T12:00:00Z,  1,     60,      2026-11-03T12:00:00Z,   2026-11-03T12:00:00Z",
        "2026-11-03T12:34:56Z,  1,     15,      2026-11-03T11:45:00Z,   2026-11-03T12:30:00Z",
        // One hour in 90-minute buckets is a single (partial) bucket.
        "2026-11-03T12:34:56Z,  1,     90,      2026-11-03T12:00:00Z,   2026-11-03T12:00:00Z",
        // Aligned to the epoch, not to the hour: 7-minute buckets start at 12:32, not 12:28.
        "2026-11-03T12:34:56Z,  1,     7,       2026-11-03T11:36:00Z,   2026-11-03T12:32:00Z",
        "2026-11-03T12:34:56Z,  720,   1440,    2026-10-05T00:00:00Z,   2026-11-03T00:00:00Z"
    })
    void bucketsAreEpochAlignedAndEndWithTheCurrentOne(
            Instant now, int hours, int bucketMinutes, Instant firstBucket, Instant lastBucket) {
        TrendWindow window = TrendWindow.ending(now, hours, bucketMinutes);

        assertThat(window.firstBucketStart()).isEqualTo(firstBucket);
        assertThat(window.lastBucketStart()).isEqualTo(lastBucket);
        assertThat(window.lastBucketStart()).isBeforeOrEqualTo(now);
    }

    @ParameterizedTest
    @CsvSource({"500, 60", "720, 87", "1, 1"})
    void upToFiveHundredPointsAreAllowed(int hours, int bucketMinutes) {
        TrendWindow.ending(Instant.parse("2026-11-03T12:00:00Z"), hours, bucketMinutes);
    }

    @ParameterizedTest
    @CsvSource({"501, 60", "720, 86", "24, 1"})
    void moreThanFiveHundredPointsAreRejected(int hours, int bucketMinutes) {
        assertThatThrownBy(() -> TrendWindow.ending(Instant.parse("2026-11-03T12:00:00Z"), hours, bucketMinutes))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("at most 500");
    }
}
