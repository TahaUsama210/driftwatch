package io.github.tahausama210.driftwatch.fleet;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Fleet average score per time bucket, oldest first, including buckets without data. */
public record FleetTrend(int bucketMinutes, List<Point> points) {

    /**
     * @param averageScore mean of the carried-forward scores; null when no host contributes
     * @param hostCount hosts whose most recent scan by the bucket end has a score
     */
    public record Point(Instant bucketStart, @Nullable BigDecimal averageScore, int hostCount) {}
}
