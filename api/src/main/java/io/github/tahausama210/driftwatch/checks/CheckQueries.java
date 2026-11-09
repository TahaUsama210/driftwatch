package io.github.tahausama210.driftwatch.checks;

import static io.github.tahausama210.driftwatch.common.jdbc.JdbcValues.instant;
import static io.github.tahausama210.driftwatch.common.jdbc.JdbcValues.uuid;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read models for the check catalog. "Latest scan" means the scan behind {@code hosts.latest_scan_id};
 * each (latest scan, check) pair is a primary-key probe into {@code scan_results}.
 */
@Repository
public class CheckQueries {

    private final JdbcClient jdbc;

    public CheckQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Every catalog check with status counts, ordered by {@link CheckSummary#BY_FLEET_IMPACT}. */
    public List<CheckSummary> listWithCounts() {
        return jdbc
                .sql("""
                        SELECT c.id, c.title, c.category, c.severity,
                               count(*) FILTER (WHERE r.status = 'PASS') AS pass_count,
                               count(*) FILTER (WHERE r.status = 'FAIL') AS fail_count,
                               count(*) FILTER (WHERE r.status = 'ERROR') AS error_count,
                               count(*) FILTER (WHERE r.status = 'NOT_APPLICABLE') AS not_applicable_count
                        FROM checks c
                        LEFT JOIN (hosts h JOIN scan_results r ON r.scan_id = h.latest_scan_id)
                               ON r.check_id = c.id
                        GROUP BY c.id
                        """)
                .query((rs, rowNum) -> mapSummary(
                        rs,
                        new StatusCounts(
                                rs.getInt("pass_count"),
                                rs.getInt("fail_count"),
                                rs.getInt("error_count"),
                                rs.getInt("not_applicable_count"))))
                .list()
                .stream()
                // Sorted here rather than in SQL so severity weights live only in Severity.
                .sorted(CheckSummary.BY_FLEET_IMPACT)
                .toList();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<CheckDetail> find(String checkId) {
        return jdbc.sql("SELECT id, title, category, severity FROM checks WHERE id = :checkId")
                .param("checkId", checkId)
                .query((rs, rowNum) -> mapSummary(rs, new StatusCounts(0, 0, 0, 0)))
                .optional()
                .map(check -> {
                    List<CheckHostStatus> hosts = hostStatuses(checkId);
                    StatusCounts counts = StatusCounts.tally(
                            hosts.stream().map(CheckHostStatus::status).toList());
                    return new CheckDetail(
                            new CheckSummary(check.id(), check.title(), check.category(), check.severity(), counts),
                            hosts);
                });
    }

    private List<CheckHostStatus> hostStatuses(String checkId) {
        return jdbc.sql("""
                        SELECT h.id AS host_id, h.hostname, r.status, r.expected, r.actual, r.evidence,
                               s.id AS scan_id, s.finished_at
                        FROM hosts h
                        JOIN scans s ON s.id = h.latest_scan_id
                        JOIN scan_results r ON r.scan_id = s.id AND r.check_id = :checkId
                        ORDER BY r.status IN ('FAIL', 'ERROR') DESC, h.hostname, h.id
                        """)
                .param("checkId", checkId)
                .query((rs, rowNum) -> new CheckHostStatus(
                        uuid(rs, "host_id"),
                        rs.getString("hostname"),
                        CheckStatus.valueOf(rs.getString("status")),
                        rs.getString("expected"),
                        rs.getString("actual"),
                        rs.getString("evidence"),
                        uuid(rs, "scan_id"),
                        instant(rs, "finished_at")))
                .list();
    }

    private static CheckSummary mapSummary(ResultSet rs, StatusCounts counts) throws SQLException {
        return new CheckSummary(
                rs.getString("id"),
                rs.getString("title"),
                Category.fromWireValue(rs.getString("category")),
                Severity.fromWireValue(rs.getString("severity")),
                counts);
    }
}
