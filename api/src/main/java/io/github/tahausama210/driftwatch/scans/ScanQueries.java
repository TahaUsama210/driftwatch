package io.github.tahausama210.driftwatch.scans;

import static io.github.tahausama210.driftwatch.common.jdbc.JdbcValues.timestamptz;

import io.github.tahausama210.driftwatch.checks.Category;
import io.github.tahausama210.driftwatch.checks.CheckStatus;
import io.github.tahausama210.driftwatch.checks.Severity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read models for scans. "Newest" and "previous" always follow the total order
 * {@code (finished_at, id)}, which {@code scans_host_finished_idx} serves.
 */
@Repository
public class ScanQueries {

    private final JdbcClient jdbc;

    public ScanQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<ScanSummary> findSummary(UUID scanId) {
        return jdbc.sql("SELECT " + ScanSummaryMapping.SELECT_LIST + " FROM scans s WHERE s.id = :scanId")
                .param("scanId", scanId)
                .query((rs, rowNum) -> ScanSummaryMapping.map(rs))
                .optional();
    }

    /** The scan, provided it belongs to {@code hostId}. */
    public Optional<ScanSummary> findForHost(UUID hostId, UUID scanId) {
        return jdbc.sql("SELECT " + ScanSummaryMapping.SELECT_LIST
                        + " FROM scans s WHERE s.id = :scanId AND s.host_id = :hostId")
                .param("scanId", scanId)
                .param("hostId", hostId)
                .query((rs, rowNum) -> ScanSummaryMapping.map(rs))
                .optional();
    }

    /** The host's newest scan, via the denormalised {@code hosts.latest_scan_id} pointer. */
    public Optional<ScanSummary> findLatestForHost(UUID hostId) {
        return jdbc.sql("SELECT " + ScanSummaryMapping.SELECT_LIST
                        + " FROM hosts h JOIN scans s ON s.id = h.latest_scan_id WHERE h.id = :hostId")
                .param("hostId", hostId)
                .query((rs, rowNum) -> ScanSummaryMapping.map(rs))
                .optional();
    }

    /** The same host's scan immediately before {@code scan}. */
    public Optional<ScanSummary> findPrevious(ScanSummary scan) {
        return jdbc.sql("""
                        SELECT %s
                        FROM scans s
                        WHERE s.host_id = :hostId AND (s.finished_at, s.id) < (:finishedAt, :scanId)
                        ORDER BY s.finished_at DESC, s.id DESC
                        LIMIT 1
                        """.formatted(ScanSummaryMapping.SELECT_LIST))
                .param("hostId", scan.hostId())
                .param("finishedAt", timestamptz(scan.finishedAt()))
                .param("scanId", scan.id())
                .query((rs, rowNum) -> ScanSummaryMapping.map(rs))
                .optional();
    }

    /** A host's scans, newest first. */
    public List<ScanSummary> listForHost(UUID hostId, int limit) {
        return jdbc.sql("""
                        SELECT %s
                        FROM scans s
                        WHERE s.host_id = :hostId
                        ORDER BY s.finished_at DESC, s.id DESC
                        LIMIT :limit
                        """.formatted(ScanSummaryMapping.SELECT_LIST))
                .param("hostId", hostId)
                .param("limit", limit)
                .query((rs, rowNum) -> ScanSummaryMapping.map(rs))
                .list();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Optional<ScanDetail> findDetail(UUID scanId) {
        return jdbc.sql("""
                        SELECT %s, h.hostname
                        FROM scans s
                        JOIN hosts h ON h.id = s.host_id
                        WHERE s.id = :scanId
                        """.formatted(ScanSummaryMapping.SELECT_LIST))
                .param("scanId", scanId)
                .query((rs, rowNum) -> new ScanDetail(ScanSummaryMapping.map(rs), rs.getString("hostname"), List.of()))
                .optional()
                .map(detail -> new ScanDetail(detail.summary(), detail.hostname(), results(scanId)));
    }

    private List<CheckResultView> results(UUID scanId) {
        return jdbc.sql("""
                        SELECT r.check_id, c.title, c.category, c.severity, r.status, r.expected, r.actual, r.evidence
                        FROM scan_results r
                        JOIN checks c ON c.id = r.check_id
                        WHERE r.scan_id = :scanId
                        ORDER BY c.category, r.check_id
                        """)
                .param("scanId", scanId)
                .query((rs, rowNum) -> new CheckResultView(
                        rs.getString("check_id"),
                        rs.getString("title"),
                        Category.fromWireValue(rs.getString("category")),
                        Severity.fromWireValue(rs.getString("severity")),
                        CheckStatus.valueOf(rs.getString("status")),
                        rs.getString("expected"),
                        rs.getString("actual"),
                        rs.getString("evidence")))
                .list();
    }
}
