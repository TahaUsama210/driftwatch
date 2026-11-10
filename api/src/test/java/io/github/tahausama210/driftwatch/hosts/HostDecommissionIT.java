package io.github.tahausama210.driftwatch.hosts;

import static io.github.tahausama210.driftwatch.support.ScanReports.ROOT_LOGIN;
import static io.github.tahausama210.driftwatch.support.ScanReports.UID0_ONLY_ROOT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.tahausama210.driftwatch.support.IntegrationTest;
import io.github.tahausama210.driftwatch.support.ScanReports;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(OutputCaptureExtension.class)
class HostDecommissionIT extends IntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private DataSource dataSource;

    @Test
    void anAdminDeletesTheHostWithItsWholeHistoryAndNothingElse() throws Exception {
        Agent doomed = enroll("machine-0001");
        Agent kept = enroll("machine-0002");
        givenHistoryWithDrift(doomed);
        givenHistoryWithDrift(kept);

        mvc.perform(delete("/api/v1/hosts/{id}", doomed.hostId()).with(asAdmin()))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(countFor("hosts", "id", doomed.hostId())).isZero();
        assertThat(countFor("scans", "host_id", doomed.hostId())).isZero();
        assertThat(countFor("drift_events", "host_id", doomed.hostId())).isZero();
        assertThat(countFor("scans", "host_id", kept.hostId())).isEqualTo(2);
        assertThat(countFor("drift_events", "host_id", kept.hostId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM scan_results"))
                .as("no orphaned results")
                .isEqualTo(4);
        assertThat(count("SELECT count(*) FROM checks"))
                .as("the shared catalog stays")
                .isEqualTo(2);
        getJson("/api/v1/hosts/{id}", doomed.hostId()).andExpect(status().isNotFound());
        getJson("/api/v1/hosts").andExpect(jsonPath("$.totalItems").value(1));
    }

    @Test
    void theAgentTokenStopsWorkingImmediately() throws Exception {
        Agent agent = enroll("machine-0001");
        ingest(agent, ScanReports.scan(NOW).result(ROOT_LOGIN, "high", "PASS"));

        mvc.perform(delete("/api/v1/hosts/{id}", agent.hostId()).with(asAdmin()))
                .andExpect(status().isNoContent());

        submitScan(agent.token(), ScanReports.scan(NOW.plusSeconds(30)).result(ROOT_LOGIN, "high", "PASS"))
                .andExpect(status().isUnauthorized());
        assertThat(count("SELECT count(*) FROM scans")).isZero();
    }

    @Test
    void anUnknownOrAlreadyDeletedHostIsNotFound() throws Exception {
        Agent agent = enroll("machine-0001");
        mvc.perform(delete("/api/v1/hosts/{id}", agent.hostId()).with(asAdmin()))
                .andExpect(status().isNoContent());

        mvc.perform(delete("/api/v1/hosts/{id}", agent.hostId()).with(asAdmin()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Host " + agent.hostId() + " does not exist."));
        mvc.perform(delete("/api/v1/hosts/{id}", UUID.randomUUID()).with(asAdmin()))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/hosts/not-a-uuid").with(asAdmin())).andExpect(status().isNotFound());
    }

    @Test
    void aViewerIsForbiddenAndNothingIsDeleted() throws Exception {
        Agent agent = enroll("machine-0001");

        mvc.perform(delete("/api/v1/hosts/{id}", agent.hostId()).with(asViewer()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(delete("/api/v1/hosts/{id}", agent.hostId())).andExpect(status().isUnauthorized());

        assertThat(countFor("hosts", "id", agent.hostId())).isOne();
    }

    @Test
    void eachDecommissionIsAuditedWithTheActorAndCounted(CapturedOutput output) throws Exception {
        Agent agent = enroll("machine-0001", "db-07.lab");
        givenHistoryWithDrift(agent);
        String subject = UUID.randomUUID().toString();
        String token = IDENTITY_PROVIDER
                .token(clock.instant(), "admin", "viewer")
                .subject(subject)
                .username("alice")
                .sign();
        double before = decommissionedTotal();

        mvc.perform(delete("/api/v1/hosts/{id}", agent.hostId()).with(bearer(token)))
                .andExpect(status().isNoContent());

        assertThat(decommissionedTotal()).isEqualTo(before + 1);
        String auditLine = output.getOut()
                .lines()
                .filter(line -> line.contains("\"Host decommissioned\"")
                        && line.contains(agent.hostId().toString()))
                .findFirst()
                .orElseThrow();
        assertThat(JsonPath.<String>read(auditLine, "$.log.logger"))
                .isEqualTo("io.github.tahausama210.driftwatch.audit");
        assertThat(JsonPath.<String>read(auditLine, "$.actor.sub")).isEqualTo(subject);
        assertThat(JsonPath.<String>read(auditLine, "$.actor.preferredUsername"))
                .isEqualTo("alice");
        assertThat(JsonPath.<String>read(auditLine, "$.hostname")).isEqualTo("db-07.lab");
        assertThat(JsonPath.<Integer>read(auditLine, "$.deletedScans")).isEqualTo(2);
        assertThat(JsonPath.<Integer>read(auditLine, "$.deletedDriftEvents")).isEqualTo(1);
        assertThat(auditLine).doesNotContain(token).doesNotContain(agent.token());
    }

    /** The decommission waits for the in-flight ingest's lock, then deletes its scan too. */
    @Test
    void aDecommissionWaitingOnAnInFlightIngestDeletesTheScanItCommitted() throws Exception {
        Agent agent = enroll("machine-0001");
        UUID scanId = UUID.randomUUID();

        MockHttpServletResponse response;
        try (Connection ingest = dataSource.getConnection()) {
            ingest.setAutoCommit(false);
            // What the ingest transaction does first: lock the host, then insert its scan.
            execute(ingest, "SELECT id FROM hosts WHERE id = ? FOR UPDATE", agent.hostId());
            execute(ingest, """
                    INSERT INTO scans (id, host_id, agent_version, started_at, finished_at, received_at, score,
                                       pass_count, fail_count, error_count, not_applicable_count)
                    VALUES (?, ?, '0.1.0', now(), now(), now(), 100.0, 1, 0, 0, 0)
                    """, scanId, agent.hostId());
            CompletableFuture<MockHttpServletResponse> decommission = async(() -> mvc.perform(
                            delete("/api/v1/hosts/{id}", agent.hostId()).with(asAdmin()))
                    .andReturn()
                    .getResponse());
            awaitLockWaiters(1);
            ingest.commit();
            response = decommission.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }

        assertThat(response.getStatus()).isEqualTo(204);
        assertThat(count("SELECT count(*) FROM scans")).isZero();
        assertThat(count("SELECT count(*) FROM hosts")).isZero();
    }

    /** A submission queued behind the decommission finds the host gone: 401, nothing written. */
    @Test
    void anIngestWaitingBehindADecommissionIsRejectedAndWritesNothing() throws Exception {
        Agent agent = enroll("machine-0001");
        String report = ScanReports.scan(NOW)
                .hostname("web-01")
                .result(ROOT_LOGIN, "high", "PASS")
                .toJson(json);
        String adminToken = adminToken();

        MockHttpServletResponse decommissionResponse;
        MockHttpServletResponse ingestResponse;
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            // Holds the host row so both requests queue on it in a known order.
            execute(blocker, "SELECT id FROM hosts WHERE id = ? FOR UPDATE", agent.hostId());
            CompletableFuture<MockHttpServletResponse> decommission = async(() -> mvc.perform(
                            delete("/api/v1/hosts/{id}", agent.hostId()).with(bearer(adminToken)))
                    .andReturn()
                    .getResponse());
            awaitLockWaiters(1);
            CompletableFuture<MockHttpServletResponse> submission =
                    async(() -> submitScan(agent.token(), report).andReturn().getResponse());
            awaitLockWaiters(2);
            blocker.commit();
            decommissionResponse = decommission.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            ingestResponse = submission.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }

        assertThat(decommissionResponse.getStatus()).isEqualTo(204);
        assertThat(ingestResponse.getStatus()).isEqualTo(401);
        assertThat(count("SELECT count(*) FROM scans")).isZero();
        assertThat(count("SELECT count(*) FROM scan_results")).isZero();
    }

    /**
     * The enrollment's insert sees the existing row (so does nothing), then its update waits on the
     * decommission's lock and finds the row deleted; it must start over and enroll a new host.
     */
    @Test
    void aReEnrollmentRacingADecommissionEnrollsTheMachineAsANewHost() throws Exception {
        Agent agent = enroll("machine-0001");

        MockHttpServletResponse response;
        try (Connection decommission = dataSource.getConnection()) {
            decommission.setAutoCommit(false);
            execute(decommission, "SELECT id FROM hosts WHERE id = ? FOR UPDATE", agent.hostId());
            CompletableFuture<MockHttpServletResponse> reEnrollment = async(
                    () -> enrollRequest("machine-0001", "web-01").andReturn().getResponse());
            awaitLockWaiters(1);
            execute(decommission, "DELETE FROM hosts WHERE id = ?", agent.hostId());
            decommission.commit();
            response = reEnrollment.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(count("SELECT count(*) FROM hosts WHERE machine_id = 'machine-0001'"))
                .isOne();
        assertThat(countFor("hosts", "id", agent.hostId())).isZero();
    }

    /** Two scans: a baseline and one that regresses the critical check, so one drift event. */
    private void givenHistoryWithDrift(Agent agent) throws Exception {
        ingest(
                agent,
                ScanReports.scan(NOW.minusSeconds(60))
                        .result(ROOT_LOGIN, "high", "PASS")
                        .result(UID0_ONLY_ROOT, "critical", "PASS"));
        ingest(
                agent,
                ScanReports.scan(NOW).result(ROOT_LOGIN, "high", "PASS").result(UID0_ONLY_ROOT, "critical", "FAIL"));
    }

    private double decommissionedTotal() throws Exception {
        String metrics = mvc.perform(get("/actuator/prometheus"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return metrics.lines()
                .filter(line -> line.startsWith("driftwatch_hosts_decommissioned_total "))
                .mapToDouble(line -> Double.parseDouble(line.substring(line.indexOf(' ') + 1)))
                .findFirst()
                .orElseThrow();
    }

    private <T> CompletableFuture<T> async(Callable<T> call) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return call.call();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        });
    }

    private void awaitLockWaiters(int waiters) throws InterruptedException {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (count("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE datname = current_database() AND wait_event_type = 'Lock'
                        """) < waiters) {
            assertThat(Instant.now())
                    .as("%d request(s) blocked on a lock", waiters)
                    .isBefore(deadline);
            Thread.sleep(20);
        }
    }

    private long countFor(String table, String column, UUID id) {
        return count("SELECT count(*) FROM %s WHERE %s = '%s'".formatted(table, column, id));
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private static void execute(Connection connection, String sql, Object... params) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                statement.setObject(i + 1, params[i]);
            }
            statement.execute();
        }
    }
}
