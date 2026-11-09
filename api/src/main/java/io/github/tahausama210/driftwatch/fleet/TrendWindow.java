package io.github.tahausama210.driftwatch.fleet;

import io.github.tahausama210.driftwatch.common.error.FieldViolation;
import io.github.tahausama210.driftwatch.common.error.InvalidRequestException;
import java.time.Instant;

/**
 * The buckets of a trend request. Buckets are aligned to the Unix epoch, so the same request made
 * at different times within a bucket returns the same bucket boundaries. The last bucket is the one
 * containing {@code now}; there are {@code ceil(hours × 60 / bucketMinutes)} buckets.
 */
record TrendWindow(Instant firstBucketStart, Instant lastBucketStart, int bucketMinutes) {

    static final int MAX_POINTS = 500;

    static TrendWindow ending(Instant now, int hours, int bucketMinutes) {
        long points = Math.ceilDiv(hours * 60L, bucketMinutes);
        if (points > MAX_POINTS) {
            throw new InvalidRequestException(new FieldViolation(
                    "bucketMinutes",
                    "is too small for hours=%d: the trend would have %d points (at most %d)"
                            .formatted(hours, points, MAX_POINTS)));
        }
        long bucketSeconds = bucketMinutes * 60L;
        Instant lastBucketStart =
                Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), bucketSeconds) * bucketSeconds);
        return new TrendWindow(
                lastBucketStart.minusSeconds((points - 1) * bucketSeconds), lastBucketStart, bucketMinutes);
    }
}
