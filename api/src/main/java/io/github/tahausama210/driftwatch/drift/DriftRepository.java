package io.github.tahausama210.driftwatch.drift;

import static io.github.tahausama210.driftwatch.common.jdbc.JdbcValues.instant;
import static io.github.tahausama210.driftwatch.common.jdbc.JdbcValues.timestamptz;
import static io.github.tahausama210.driftwatch.common.jdbc.JdbcValues.uuid;

import io.github.tahausama210.driftwatch.checks.Category;
import io.github.tahausama210.driftwatch.checks.CheckStatus;
import io.github.tahausama210.driftwatch.checks.Severity;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Status diffs between scans and the persisted drift event log. */
@Repository
public class DriftRepository {

    private final JdbcClient jdbc;

    public DriftRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Checks present in both scans whose status differs, with their catalog metadata. Checks that
     * appear in only one of the scans are not compared. Two primary-key range scans and a hash join.
     */
    public List<DriftChange> diff(UUID fromScanId, UUID toScanId) {
        return jdbc.sql("""
                        SELECT a.check_id, c.title, c.category, c.severity,
                               a.status AS from_status, b.status AS to_status,
                               a.evidence AS from_evidence, b.evidence AS to_evidence
                        FROM scan_results a
                        JOIN scan_results b ON b.scan_id = :toScanId AND b.check_id = a.check_id
                        JOIN checks c ON c.id = a.check_id
                        WHERE a.scan_id = :fromScanId AND a.status <> b.status
                        """)
                .param("fromScanId", fromScanId)
                .param("toScanId", toScanId)
                .query((rs, rowNum) -> mapChange(rs))
                .list();
    }

    public void insertEvents(
            UUID hostId, UUID previousScanId, UUID scanId, Instant detectedAt, List<DriftChange> changes) {
        if (changes.isEmpty()) {
            return;
        }
        jdbc.sql("""
                        INSERT INTO drift_events (host_id, scan_id, previous_scan_id, check_id, from_status,
                                                  to_status, from_evidence, to_evidence, direction, detected_at)
                        SELECT :hostId, :scanId, :previousScanId, e.check_id, e.from_status, e.to_status,
                               e.from_evidence, e.to_evidence, e.direction, :detectedAt
                        FROM unnest(CAST(:checkIds AS text[]), CAST(:fromStatuses AS text[]),
                                    CAST(:toStatuses AS text[]), CAST(:fromEvidence AS text[]),
                                    CAST(:toEvidence AS text[]), CAST(:directions AS text[]))
                             AS e (check_id, from_status, to_status, from_evidence, to_evidence, direction)
                        """)
                .param("hostId", hostId)
                .param("scanId", scanId)
                .param("previousScanId", previousScanId)
                .param("detectedAt", timestamptz(detectedAt))
                .param("checkIds", changes.stream().map(DriftChange::checkId).toArray(String[]::new))
                .param(
                        "fromStatuses",
                        changes.stream().map(c -> c.fromStatus().name()).toArray(String[]::new))
                .param(
                        "toStatuses",
                        changes.stream().map(c -> c.toStatus().name()).toArray(String[]::new))
                .param(
                        "fromEvidence",
                        changes.stream().map(DriftChange::fromEvidence).toArray(String[]::new))
                .param(
                        "toEvidence",
                        changes.stream().map(DriftChange::toEvidence).toArray(String[]::new))
                .param(
                        "directions",
                        changes.stream().map(c -> c.direction().name()).toArray(String[]::new))
                .update();
    }

    public void deleteEventsForScan(UUID scanId) {
        jdbc.sql("DELETE FROM drift_events WHERE scan_id = :scanId")
                .param("scanId", scanId)
                .update();
    }

    /** Newest first; served by the {@code detected_at} indexes. */
    public List<DriftEvent> listEvents(@Nullable UUID hostId, @Nullable DriftDirection direction, int limit) {
        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        if (hostId != null) {
            conditions.add("e.host_id = :hostId");
            params.put("hostId", hostId);
        }
        if (direction != null) {
            conditions.add("e.direction = :direction");
            params.put("direction", direction.name());
        }
        String where = conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions);
        return jdbc.sql("""
                        SELECT e.id, e.host_id, h.hostname, e.scan_id, e.detected_at, e.check_id, c.title,
                               c.category, c.severity, e.from_status, e.to_status, e.from_evidence,
                               e.to_evidence
                        FROM drift_events e
                        JOIN hosts h ON h.id = e.host_id
                        JOIN checks c ON c.id = e.check_id
                        %s
                        ORDER BY e.detected_at DESC, e.id DESC
                        LIMIT :limit
                        """.formatted(where))
                .params(params)
                .param("limit", limit)
                .query((rs, rowNum) -> new DriftEvent(
                        uuid(rs, "id"),
                        uuid(rs, "host_id"),
                        rs.getString("hostname"),
                        uuid(rs, "scan_id"),
                        instant(rs, "detected_at"),
                        mapChange(rs)))
                .list();
    }

    private static DriftChange mapChange(ResultSet rs) throws SQLException {
        CheckStatus from = CheckStatus.valueOf(rs.getString("from_status"));
        CheckStatus to = CheckStatus.valueOf(rs.getString("to_status"));
        return new DriftChange(
                rs.getString("check_id"),
                rs.getString("title"),
                Category.fromWireValue(rs.getString("category")),
                Severity.fromWireValue(rs.getString("severity")),
                from,
                to,
                rs.getString("from_evidence"),
                rs.getString("to_evidence"),
                // Derived rather than read back, so the direction table has a single implementation.
                DriftDirection.between(from, to)
                        .orElseThrow(() -> new SQLException("Drift row without a status change")));
    }
}
