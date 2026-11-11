package io.github.tahausama210.driftwatch.fleet;

import static io.github.tahausama210.driftwatch.common.jdbc.JdbcValues.instant;
import static io.github.tahausama210.driftwatch.common.jdbc.JdbcValues.nullableInstant;
import static io.github.tahausama210.driftwatch.common.jdbc.JdbcValues.timestamptz;
import static io.github.tahausama210.driftwatch.common.jdbc.JdbcValues.uuid;

import io.github.tahausama210.driftwatch.checks.Severity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    /**
     * One host as the per-host gauges see it.
     *
     * @param scanned whether the host has a latest scan at all
     * @param score the latest scan's score; null when unscanned or nothing was applicable
     * @param failingBySeverity FAIL and ERROR results of the latest scan; severities with none are absent
     */
    record HostSnapshot(
            UUID hostId,
            String hostname,
            Instant lastSeenAt,
            boolean scanned,
            @Nullable BigDecimal score,
            Map<Severity, Integer> failingBySeverity) {}

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

    /**
     * Every host with its latest scan's score and failing results by severity, through the
     * latest-scan pointer, so the cost is one scan's results per host, not the history. Severity is
     * the catalog's, as in the fleet summary.
     */
    List<HostSnapshot> hostSnapshots() {
        return jdbc.sql("""
                        SELECT h.id, h.hostname, h.last_seen_at, h.latest_scan_id IS NOT NULL AS scanned, s.score,
                               count(*) FILTER (WHERE c.severity = 'low') AS failing_low,
                               count(*) FILTER (WHERE c.severity = 'medium') AS failing_medium,
                               count(*) FILTER (WHERE c.severity = 'high') AS failing_high,
                               count(*) FILTER (WHERE c.severity = 'critical') AS failing_critical
                        FROM hosts h
                        LEFT JOIN scans s ON s.id = h.latest_scan_id
                        LEFT JOIN scan_results r ON r.scan_id = h.latest_scan_id AND r.status IN ('FAIL', 'ERROR')
                        LEFT JOIN checks c ON c.id = r.check_id
                        GROUP BY h.id, s.id
                        """)
                .query((rs, rowNum) -> {
                    Map<Severity, Integer> failing = new EnumMap<>(Severity.class);
                    for (Severity severity : Severity.values()) {
                        int count = rs.getInt("failing_" + severity.wireValue());
                        if (count > 0) {
                            failing.put(severity, count);
                        }
                    }
                    return new HostSnapshot(
                            uuid(rs, "id"),
                            rs.getString("hostname"),
                            instant(rs, "last_seen_at"),
                            rs.getBoolean("scanned"),
                            rs.getBigDecimal("score"),
                            failing);
                })
                .list();
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
