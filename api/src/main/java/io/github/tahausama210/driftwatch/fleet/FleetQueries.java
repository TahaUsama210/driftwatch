package io.github.tahausama210.driftwatch.fleet;

import static io.github.tahausama210.driftwatch.common.jdbc.JdbcValues.instant;
import static io.github.tahausama210.driftwatch.common.jdbc.JdbcValues.nullableInstant;
import static io.github.tahausama210.driftwatch.common.jdbc.JdbcValues.timestamptz;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Fleet-wide aggregates. Averages are rounded in SQL with {@code round(numeric, 1)}, which rounds
 * half away from zero, i.e. half-up for scores (never negative).
 */
@Repository
class FleetQueries {

    record Headline(
            long hostCount,
            long onlineCount,
            @Nullable BigDecimal averageScore,
            @Nullable Instant lastScanAt) {}

    private final JdbcClient jdbc;

    FleetQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Headline headline(Instant staleBefore) {
        return jdbc.sql("""
                        SELECT count(*) AS host_count,
                               count(*) FILTER (WHERE h.last_seen_at >= :staleBefore) AS online_count,
                               round(avg(s.score), 1) AS average_score,
                               max(s.finished_at) AS last_scan_at
                        FROM hosts h
                        LEFT JOIN scans s ON s.id = h.latest_scan_id
                        """)
                .param("staleBefore", timestamptz(staleBefore))
                .query((rs, rowNum) -> new Headline(
                        rs.getLong("host_count"),
                        rs.getLong("online_count"),
                        rs.getBigDecimal("average_score"),
                        nullableInstant(rs, "last_scan_at")))
                .single();
    }

    long hostsWithCriticalFailures() {
        return jdbc.sql("""
                        SELECT count(DISTINCT h.id)
                        FROM hosts h
                        JOIN scan_results r ON r.scan_id = h.latest_scan_id
                        JOIN checks c ON c.id = r.check_id
                        WHERE c.severity = 'critical' AND r.status IN ('FAIL', 'ERROR')
                        """).query(Long.class).single();
    }

    /**
     * One row per bucket. For every bucket and host, a lateral index probe on
     * {@code scans_host_finished_idx} picks the host's newest scan that finished before the next
     * bucket starts (carry-forward); hosts with no such scan contribute nothing. The
     * {@code LEFT JOIN hosts ... ON true} keeps empty buckets when there are no hosts at all.
     * Intervals are whole minutes, so bucket arithmetic is unaffected by time zones or DST.
     */
    List<FleetTrend.Point> trend(TrendWindow window) {
        return jdbc.sql("""
                        SELECT b.bucket_start,
                               round(avg(latest.score), 1) AS average_score,
                               count(latest.score) AS host_count
                        FROM generate_series(CAST(:firstBucketStart AS timestamptz),
                                             CAST(:lastBucketStart AS timestamptz),
                                             make_interval(mins => :bucketMinutes)) AS b (bucket_start)
                        LEFT JOIN hosts h ON true
                        LEFT JOIN LATERAL (
                            SELECT s.score
                            FROM scans s
                            WHERE s.host_id = h.id
                              AND s.finished_at < b.bucket_start + make_interval(mins => :bucketMinutes)
                            ORDER BY s.finished_at DESC, s.id DESC
                            LIMIT 1
                        ) latest ON true
                        GROUP BY b.bucket_start
                        ORDER BY b.bucket_start
                        """)
                .param("firstBucketStart", timestamptz(window.firstBucketStart()))
                .param("lastBucketStart", timestamptz(window.lastBucketStart()))
                .param("bucketMinutes", window.bucketMinutes())
                .query((rs, rowNum) -> new FleetTrend.Point(
                        instant(rs, "bucket_start"), rs.getBigDecimal("average_score"), rs.getInt("host_count")))
                .list();
    }
}
