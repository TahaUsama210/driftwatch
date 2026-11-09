package io.github.tahausama210.driftwatch.hosts;

import static io.github.tahausama210.driftwatch.common.jdbc.JdbcValues.instant;
import static io.github.tahausama210.driftwatch.common.jdbc.JdbcValues.timestamptz;
import static io.github.tahausama210.driftwatch.common.jdbc.JdbcValues.uuid;

import io.github.tahausama210.driftwatch.scans.ScanSummaryMapping;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read models for hosts. Each host is joined to its newest scan through the denormalised
 * {@code hosts.latest_scan_id} pointer, so listing N hosts touches N scan rows, not the history.
 *
 * <p>The hostname filter is an {@code ILIKE '%…%'} scan of {@code hosts}; a fleet has hundreds of
 * rows, not millions, so a trigram index would cost more on writes than it saves on reads.
 */
@Repository
public class HostQueries {

    private static final String SELECT_HOST = """
            SELECT h.id, h.hostname, h.os_id, h.os_version, h.os_pretty_name, h.kernel, h.arch,
                   h.agent_version, h.enrolled_at, h.last_seen_at, %s
            FROM hosts h
            LEFT JOIN scans s ON s.id = h.latest_scan_id
            """.formatted(ScanSummaryMapping.SELECT_LIST);

    private final JdbcClient jdbc;

    public HostQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean exists(UUID hostId) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM hosts WHERE id = :hostId)")
                .param("hostId", hostId)
                .query(Boolean.class)
                .single();
    }

    public Optional<Host> find(UUID hostId, Instant staleBefore) {
        return jdbc.sql(SELECT_HOST + "WHERE h.id = :hostId")
                .param("hostId", hostId)
                .query((rs, rowNum) -> mapHost(rs, staleBefore))
                .optional();
    }

    /** Runs the count and the page on one snapshot (repeatable read) so they agree with each other. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public HostPage search(HostSearch search, Instant staleBefore) {
        List<String> conditions = new ArrayList<>();
        Map<String, Object> params = new HashMap<>();
        if (search.query() != null && !search.query().isBlank()) {
            conditions.add("h.hostname ILIKE :hostnamePattern ESCAPE '\\'");
            params.put("hostnamePattern", "%" + escapeLikePattern(search.query()) + "%");
        }
        if (search.status() != null) {
            conditions.add(
                    search.status() == HostStatus.STALE
                            ? "h.last_seen_at < :staleBefore"
                            : "h.last_seen_at >= :staleBefore");
            params.put("staleBefore", timestamptz(staleBefore));
        }
        String where = conditions.isEmpty() ? "" : "WHERE " + String.join(" AND ", conditions) + "\n";

        long totalItems = jdbc.sql("SELECT count(*) FROM hosts h " + where)
                .params(params)
                .query(Long.class)
                .single();
        List<Host> items = jdbc.sql(
                        SELECT_HOST + where + "ORDER BY " + orderBy(search) + "\nLIMIT :limit OFFSET :offset")
                .params(params)
                .param("limit", search.size())
                .param("offset", search.offset())
                .query((rs, rowNum) -> mapHost(rs, staleBefore))
                .list();
        return new HostPage(items, search.page(), search.size(), totalItems);
    }

    /** Built only from enum constants, never from request text. Ties break on id for stable paging. */
    private static String orderBy(HostSearch search) {
        String direction = search.direction().sql();
        return switch (search.sort()) {
            case HOSTNAME -> "h.hostname " + direction + ", h.id " + direction;
            // Hosts without a scored scan sort last in either direction.
            case SCORE -> "s.score " + direction + " NULLS LAST, h.hostname, h.id";
            case LAST_SEEN_AT -> "h.last_seen_at " + direction + ", h.id " + direction;
        };
    }

    private static String escapeLikePattern(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Host mapHost(ResultSet rs, Instant staleBefore) throws SQLException {
        Instant lastSeenAt = instant(rs, "last_seen_at");
        return new Host(
                uuid(rs, "id"),
                rs.getString("hostname"),
                new OsInfo(rs.getString("os_id"), rs.getString("os_version"), rs.getString("os_pretty_name")),
                rs.getString("kernel"),
                rs.getString("arch"),
                rs.getString("agent_version"),
                instant(rs, "enrolled_at"),
                lastSeenAt,
                HostStatus.of(lastSeenAt, staleBefore),
                ScanSummaryMapping.mapNullable(rs));
    }
}
