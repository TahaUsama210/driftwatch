package io.github.tahausama210.driftwatch.checks;

import static io.github.tahausama210.driftwatch.common.jdbc.JdbcValues.timestamptz;

import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Keeps the check catalog in step with the definitions agents report. */
@Repository
public class CheckCatalogRepository {

    private final JdbcClient jdbc;

    public CheckCatalogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts unknown checks and updates changed ones, in one statement.
     *
     * <p>Rows whose stored definition already matches are filtered out before the upsert. That
     * matters for concurrency: {@code ON CONFLICT DO UPDATE} row-locks every conflicting row until
     * the ingest transaction commits, so upserting unchanged rows would make every concurrent ingest
     * in the fleet queue on the same catalog rows. In the steady state this statement writes and
     * locks nothing. Changed rows are written in id order so two transactions can never lock the
     * same rows in opposite orders and deadlock.
     */
    public void upsert(List<CheckDefinition> definitions, Instant now) {
        jdbc.sql("""
                        INSERT INTO checks (id, title, category, severity, first_seen_at, updated_at)
                        SELECT d.id, d.title, d.category, d.severity, :now, :now
                        FROM unnest(CAST(:ids AS text[]), CAST(:titles AS text[]),
                                    CAST(:categories AS text[]), CAST(:severities AS text[]))
                             AS d (id, title, category, severity)
                        WHERE NOT EXISTS (
                            SELECT 1 FROM checks c
                            WHERE c.id = d.id AND c.title = d.title
                              AND c.category = d.category AND c.severity = d.severity)
                        ORDER BY d.id
                        ON CONFLICT (id) DO UPDATE
                        SET title = EXCLUDED.title,
                            category = EXCLUDED.category,
                            severity = EXCLUDED.severity,
                            updated_at = EXCLUDED.updated_at
                        """)
                .param("now", timestamptz(now))
                .param("ids", definitions.stream().map(CheckDefinition::id).toArray(String[]::new))
                .param(
                        "titles",
                        definitions.stream().map(CheckDefinition::title).toArray(String[]::new))
                .param(
                        "categories",
                        definitions.stream().map(d -> d.category().wireValue()).toArray(String[]::new))
                .param(
                        "severities",
                        definitions.stream().map(d -> d.severity().wireValue()).toArray(String[]::new))
                .update();
    }
}
