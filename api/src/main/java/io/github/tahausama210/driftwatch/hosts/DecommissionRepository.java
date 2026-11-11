package io.github.tahausama210.driftwatch.hosts;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Deletes a host and everything recorded about it. Every method expects the decommission transaction.
 *
 * <p>The schema has no {@code ON DELETE CASCADE}, so that removing history is always an explicit
 * statement rather than a side effect of deleting a host row; the deletes here run child-first in
 * foreign-key order. Each one is driven by an index: {@code drift_events_host_detected_idx},
 * {@code scans_host_finished_idx} and the {@code scan_results} primary key (see {@code V2} for the
 * index that keeps the foreign-key checks on deleted scans cheap).
 */
@Repository
class DecommissionRepository {

    /** What was deleted, for the audit log. */
    record DeletedHost(UUID hostId, String hostname, int scans, int results, int driftEvents) {}

    private final JdbcClient jdbc;

    DecommissionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Locks the host row first, the same lock an ingest takes, so a decommission and an ingest of the
     * same host are serialised: whichever commits second sees the other's outcome.
     *
     * @return empty when no such host exists (or a concurrent decommission deleted it first)
     */
    Optional<DeletedHost> delete(UUID hostId) {
        Optional<String> hostname = jdbc.sql("SELECT hostname FROM hosts WHERE id = :hostId FOR UPDATE")
                .param("hostId", hostId)
                .query(String.class)
                .optional();
        if (hostname.isEmpty()) {
            return Optional.empty();
        }
        // hosts.latest_scan_id references scans, so the pointer goes before the scans do.
        jdbc.sql("UPDATE hosts SET latest_scan_id = NULL WHERE id = :hostId")
                .param("hostId", hostId)
                .update();
        int driftEvents = jdbc.sql("DELETE FROM drift_events WHERE host_id = :hostId")
                .param("hostId", hostId)
                .update();
        int results = jdbc.sql("""
                        DELETE FROM scan_results r
                        USING scans s
                        WHERE r.scan_id = s.id AND s.host_id = :hostId
                        """).param("hostId", hostId).update();
        int scans = jdbc.sql("DELETE FROM scans WHERE host_id = :hostId")
                .param("hostId", hostId)
                .update();
        jdbc.sql("DELETE FROM hosts WHERE id = :hostId").param("hostId", hostId).update();
        return Optional.of(new DeletedHost(hostId, hostname.get(), scans, results, driftEvents));
    }
}
