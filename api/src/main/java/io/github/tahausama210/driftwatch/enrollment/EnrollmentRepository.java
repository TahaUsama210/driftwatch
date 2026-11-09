package io.github.tahausama210.driftwatch.enrollment;

import static io.github.tahausama210.driftwatch.common.jdbc.JdbcValues.timestamptz;

import io.github.tahausama210.driftwatch.hosts.HostFacts;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Upserts hosts by machine id. */
@Repository
class EnrollmentRepository {

    record EnrolledHost(UUID hostId, boolean created) {}

    private final JdbcClient jdbc;

    EnrollmentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts the host, or rotates the token and refreshes the facts of the host already enrolled
     * with this machine id. {@code ON CONFLICT DO NOTHING} followed by {@code UPDATE} (rather than
     * {@code DO UPDATE}) tells the two cases apart without relying on system columns, and stays
     * correct under concurrency: if another enrollment inserts the same machine id first, the insert
     * waits for it and does nothing, and the update then finds the committed row.
     */
    EnrolledHost upsert(String machineId, String agentVersion, HostFacts facts, String tokenHash, Instant now) {
        Map<String, Object> params = Map.of(
                "machineId", machineId,
                "tokenHash", tokenHash,
                "hostname", facts.hostname(),
                "osId", facts.os().id(),
                "osPrettyName", facts.os().prettyName(),
                "kernel", facts.kernel(),
                "arch", facts.arch(),
                "agentVersion", agentVersion,
                "now", timestamptz(now));
        Optional<UUID> inserted = jdbc.sql("""
                        INSERT INTO hosts (machine_id, token_hash, hostname, os_id, os_version, os_pretty_name,
                                           kernel, arch, agent_version, enrolled_at, last_seen_at)
                        VALUES (:machineId, :tokenHash, :hostname, :osId, :osVersion, :osPrettyName,
                                :kernel, :arch, :agentVersion, :now, :now)
                        ON CONFLICT (machine_id) DO NOTHING
                        RETURNING id
                        """)
                .params(params)
                .param("osVersion", facts.os().version())
                .query(UUID.class)
                .optional();
        if (inserted.isPresent()) {
            return new EnrolledHost(inserted.get(), true);
        }
        UUID existing = jdbc.sql("""
                        UPDATE hosts
                        SET token_hash = :tokenHash, hostname = :hostname, os_id = :osId,
                            os_version = :osVersion, os_pretty_name = :osPrettyName, kernel = :kernel,
                            arch = :arch, agent_version = :agentVersion, last_seen_at = :now
                        WHERE machine_id = :machineId
                        RETURNING id
                        """)
                .params(params)
                .param("osVersion", facts.os().version())
                .query(UUID.class)
                .single();
        return new EnrolledHost(existing, false);
    }
}
