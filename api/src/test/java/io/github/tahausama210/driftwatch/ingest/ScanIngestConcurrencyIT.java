package io.github.tahausama210.driftwatch.ingest;

import static io.github.tahausama210.driftwatch.support.ScanReports.ROOT_LOGIN;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import io.github.tahausama210.driftwatch.security.AgentTokens;
import io.github.tahausama210.driftwatch.support.IntegrationTest;
import io.github.tahausama210.driftwatch.support.ScanReports;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The two races the ingest transaction guards against under the host row lock. Each is made
 * deterministic by holding the conflicting write open in a second transaction until the submission
 * is observed waiting on its lock, and only then committing it.
 */
class ScanIngestConcurrencyIT extends IntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private DataSource dataSource;

    @Test
    void aTokenRotatedWhileTheSubmissionWaitsForTheHostLockIsRejected() throws Exception {
        Agent agent = enroll("machine-0001");

        MockHttpServletResponse response;
        try (Connection reEnrollment = dataSource.getConnection()) {
            reEnrollment.setAutoCommit(false);
            // A re-enrollment in flight: it holds the host row, and the old token still authenticates.
            execute(
                    reEnrollment,
                    "UPDATE hosts SET token_hash = ? WHERE id = ?",
                    AgentTokens.hash(AgentTokens.generate()),
                    agent.hostId());
            CompletableFuture<MockHttpServletResponse> submission =
                    submitAsync(agent.token(), ScanReports.scan(NOW).result(ROOT_LOGIN, "high", "PASS"));
            awaitSubmissionWaitingOnALock();
            reEnrollment.commit();
            response = submission.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(count("SELECT count(*) FROM scans")).isZero();
    }

    @Test
    void aScanIdCommittedByAnotherHostWhileTheSubmissionIsInFlightIsAConflict() throws Exception {
        Agent agent = enroll("machine-0001");
        Agent other = enroll("machine-0002");
        ScanReports.Builder report = ScanReports.scan(NOW).result(ROOT_LOGIN, "high", "PASS");

        MockHttpServletResponse response;
        try (Connection otherIngest = dataSource.getConnection()) {
            otherIngest.setAutoCommit(false);
            // The other host's ingest of the same scan id, inserted but not yet committed: invisible
            // to the ownership check, so only the primary key can catch it.
            execute(otherIngest, """
                    INSERT INTO scans (id, host_id, agent_version, started_at, finished_at, received_at, score,
                                       pass_count, fail_count, error_count, not_applicable_count)
                    VALUES (?, ?, '0.1.0', now(), now(), now(), 100.0, 1, 0, 0, 0)
                    """, report.scanId(), other.hostId());
            CompletableFuture<MockHttpServletResponse> submission = submitAsync(agent.token(), report);
            awaitSubmissionWaitingOnALock();
            otherIngest.commit();
            response = submission.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(JsonPath.<String>read(response.getContentAsString(), "$.detail"))
                .isEqualTo("Scan " + report.scanId() + " was already submitted by another host.");
        assertThat(count("SELECT count(*) FROM scans WHERE host_id = '%s'".formatted(agent.hostId())))
                .isZero();
    }

    private CompletableFuture<MockHttpServletResponse> submitAsync(String token, ScanReports.Builder report) {
        String body = report.hostnameIfUnset("web-01").toJson(json);
        return CompletableFuture.supplyAsync(() -> {
            try {
                return submitScan(token, body).andReturn().getResponse();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }

    private void awaitSubmissionWaitingOnALock() throws InterruptedException {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (count("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE datname = current_database() AND wait_event_type = 'Lock'
                        """) == 0) {
            assertThat(Instant.now()).as("submission blocked on a lock").isBefore(deadline);
            Thread.sleep(20);
        }
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private static void execute(Connection connection, String sql, Object... params) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            statement.executeUpdate();
        }
    }
}
