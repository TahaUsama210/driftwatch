package io.github.tahausama210.driftwatch.ingest;

import static io.github.tahausama210.driftwatch.common.jdbc.JdbcValues.instant;
import static io.github.tahausama210.driftwatch.common.jdbc.JdbcValues.timestamptz;
import static io.github.tahausama210.driftwatch.common.jdbc.JdbcValues.uuid;

import io.github.tahausama210.driftwatch.checks.StatusCounts;
import io.github.tahausama210.driftwatch.hosts.HostFacts;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Writes performed while ingesting a scan. Every method expects the ingest transaction. */
@Repository
class IngestRepository {

    /** A scan's position in its host's history. */
    record ScanRef(UUID id, Instant finishedAt) {}

    record NewScan(
            UUID id,
            UUID hostId,
            String agentVersion,
            Instant startedAt,
            Instant finishedAt,
            Instant receivedAt,
            @Nullable BigDecimal score,
            StatusCounts counts) {}

    private final JdbcClient jdbc;

    IngestRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Locks the host row until the transaction ends, provided the token hash still matches.
     *
     * @return false when the token was rotated after the request was authenticated
     */
    boolean lockHost(UUID hostId, String tokenHash) {
        return jdbc.sql("SELECT id FROM hosts WHERE id = :hostId AND token_hash = :tokenHash FOR UPDATE")
                .param("hostId", hostId)
                .param("tokenHash", tokenHash)
                .query(UUID.class)
                .optional()
                .isPresent();
    }

    Optional<UUID> findScanOwner(UUID scanId) {
        return jdbc.sql("SELECT host_id FROM scans WHERE id = :scanId")
                .param("scanId", scanId)
                .query(UUID.class)
                .optional();
    }

    void insertScan(NewScan scan) {
        jdbc.sql("""
                        INSERT INTO scans (id, host_id, agent_version, started_at, finished_at, received_at, score,
                                           pass_count, fail_count, error_count, not_applicable_count)
                        VALUES (:id, :hostId, :agentVersion, :startedAt, :finishedAt, :receivedAt, :score,
                                :pass, :fail, :error, :notApplicable)
                        """)
                .param("id", scan.id())
                .param("hostId", scan.hostId())
                .param("agentVersion", scan.agentVersion())
                .param("startedAt", timestamptz(scan.startedAt()))
                .param("finishedAt", timestamptz(scan.finishedAt()))
                .param("receivedAt", timestamptz(scan.receivedAt()))
                .param("score", scan.score())
                .param("pass", scan.counts().pass())
                .param("fail", scan.counts().fail())
                .param("error", scan.counts().error())
                .param("notApplicable", scan.counts().notApplicable())
                .update();
    }

    /** All results in one statement: the arrays are expanded server-side by {@code unnest}. */
    void insertResults(UUID scanId, List<CheckResultReport> results) {
        jdbc.sql("""
                        INSERT INTO scan_results (scan_id, check_id, status, expected, actual, evidence)
                        SELECT :scanId, r.check_id, r.status, r.expected, r.actual, r.evidence
                        FROM unnest(CAST(:checkIds AS text[]), CAST(:statuses AS text[]), CAST(:expected AS text[]),
                                    CAST(:actual AS text[]), CAST(:evidence AS text[]))
                             AS r (check_id, status, expected, actual, evidence)
                        """)
                .param("scanId", scanId)
                .param(
                        "checkIds",
                        results.stream().map(CheckResultReport::checkId).toArray(String[]::new))
                .param("statuses", results.stream().map(r -> r.status().name()).toArray(String[]::new))
                .param(
                        "expected",
                        results.stream().map(CheckResultReport::expected).toArray(String[]::new))
                .param("actual", results.stream().map(CheckResultReport::actual).toArray(String[]::new))
                .param(
                        "evidence",
                        results.stream().map(CheckResultReport::evidence).toArray(String[]::new))
                .update();
    }

    /** The host's scan immediately before {@code scan} in {@code (finished_at, id)} order. */
    Optional<ScanRef> findPrevious(UUID hostId, ScanRef scan) {
        return neighbour(hostId, scan, "<", "DESC");
    }

    /** The host's scan immediately after {@code scan}; present only when {@code scan} arrived late. */
    Optional<ScanRef> findNext(UUID hostId, ScanRef scan) {
        return neighbour(hostId, scan, ">", "ASC");
    }

    private Optional<ScanRef> neighbour(UUID hostId, ScanRef scan, String comparison, String order) {
        return jdbc.sql("""
                        SELECT id, finished_at
                        FROM scans
                        WHERE host_id = :hostId AND (finished_at, id) %s (:finishedAt, :scanId)
                        ORDER BY finished_at %s, id %s
                        LIMIT 1
                        """.formatted(comparison, order, order))
                .param("hostId", hostId)
                .param("finishedAt", timestamptz(scan.finishedAt()))
                .param("scanId", scan.id())
                .query((rs, rowNum) -> new ScanRef(uuid(rs, "id"), instant(rs, "finished_at")))
                .optional();
    }

    /** Points the host at its new newest scan and refreshes its facts from that scan's report. */
    void recordLatestScan(UUID hostId, UUID scanId, String agentVersion, HostFacts facts, Instant seenAt) {
        jdbc.sql("""
                        UPDATE hosts
                        SET latest_scan_id = :scanId, last_seen_at = :seenAt, agent_version = :agentVersion,
                            hostname = :hostname, os_id = :osId, os_version = :osVersion,
                            os_pretty_name = :osPrettyName, kernel = :kernel, arch = :arch
                        WHERE id = :hostId
                        """)
                .param("hostId", hostId)
                .param("scanId", scanId)
                .param("seenAt", timestamptz(seenAt))
                .param("agentVersion", agentVersion)
                .param("hostname", facts.hostname())
                .param("osId", facts.os().id())
                .param("osVersion", facts.os().version())
                .param("osPrettyName", facts.os().prettyName())
                .param("kernel", facts.kernel())
                .param("arch", facts.arch())
                .update();
    }

    /** Records contact without touching facts or the latest-scan pointer (late scans). */
    void touch(UUID hostId, Instant seenAt) {
        jdbc.sql("UPDATE hosts SET last_seen_at = :seenAt WHERE id = :hostId")
                .param("hostId", hostId)
                .param("seenAt", timestamptz(seenAt))
                .update();
    }
}
