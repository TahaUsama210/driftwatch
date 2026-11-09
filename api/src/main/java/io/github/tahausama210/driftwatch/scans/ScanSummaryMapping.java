package io.github.tahausama210.driftwatch.scans;

import static io.github.tahausama210.driftwatch.common.jdbc.JdbcValues.instant;
import static io.github.tahausama210.driftwatch.common.jdbc.JdbcValues.nullableUuid;
import static io.github.tahausama210.driftwatch.common.jdbc.JdbcValues.uuid;

import io.github.tahausama210.driftwatch.checks.StatusCounts;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.jspecify.annotations.Nullable;

/**
 * The select list and row mapping for {@link ScanSummary}, shared by every query that returns one.
 * Queries must alias the {@code scans} table as {@code s}; the columns are prefixed so they can sit
 * next to host columns in the same row.
 */
public final class ScanSummaryMapping {

    public static final String SELECT_LIST = """
            s.id AS scan_id, s.host_id AS scan_host_id, s.agent_version AS scan_agent_version,
            s.started_at AS scan_started_at, s.finished_at AS scan_finished_at,
            s.received_at AS scan_received_at, s.score AS scan_score,
            s.pass_count AS scan_pass_count, s.fail_count AS scan_fail_count,
            s.error_count AS scan_error_count, s.not_applicable_count AS scan_not_applicable_count""";

    private ScanSummaryMapping() {}

    public static ScanSummary map(ResultSet rs) throws SQLException {
        return new ScanSummary(
                uuid(rs, "scan_id"),
                uuid(rs, "scan_host_id"),
                rs.getString("scan_agent_version"),
                instant(rs, "scan_started_at"),
                instant(rs, "scan_finished_at"),
                instant(rs, "scan_received_at"),
                rs.getBigDecimal("scan_score"),
                new StatusCounts(
                        rs.getInt("scan_pass_count"),
                        rs.getInt("scan_fail_count"),
                        rs.getInt("scan_error_count"),
                        rs.getInt("scan_not_applicable_count")));
    }

    /** For outer joins: null when the row has no scan. */
    public static @Nullable ScanSummary mapNullable(ResultSet rs) throws SQLException {
        return nullableUuid(rs, "scan_id") == null ? null : map(rs);
    }
}
