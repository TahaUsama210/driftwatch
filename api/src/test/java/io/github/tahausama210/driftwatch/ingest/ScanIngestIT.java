package io.github.tahausama210.driftwatch.ingest;

import static io.github.tahausama210.driftwatch.support.ScanReports.ASLR;
import static io.github.tahausama210.driftwatch.support.ScanReports.CRONTAB;
import static io.github.tahausama210.driftwatch.support.ScanReports.IP_FORWARDING;
import static io.github.tahausama210.driftwatch.support.ScanReports.ROOT_LOGIN;
import static io.github.tahausama210.driftwatch.support.ScanReports.UID0_ONLY_ROOT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.tahausama210.driftwatch.support.IntegrationTest;
import io.github.tahausama210.driftwatch.support.ScanReports;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;

class ScanIngestIT extends IntegrationTest {

    @Test
    void newScanIsStoredScoredAndReturnedAsCreated() throws Exception {
        Agent agent = enroll("machine-0001");
        // PASS high 3 + PASS critical 5 + FAIL medium 2 + ERROR high 3, NOT_APPLICABLE excluded: 8/13 = 61.538...
        ScanReports.Builder report = ScanReports.scan(NOW.minusSeconds(30))
                .result(ROOT_LOGIN, "high", "PASS")
                .result(UID0_ONLY_ROOT, "critical", "PASS")
                .result(IP_FORWARDING, "medium", "FAIL")
                .result(ASLR, "high", "ERROR")
                .result(CRONTAB, "low", "NOT_APPLICABLE");

        submitScan(agent.token(), report)
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/scans/" + report.scanId()))
                .andExpect(jsonPath("$.id").value(report.scanId().toString()))
                .andExpect(jsonPath("$.hostId").value(agent.hostId().toString()))
                .andExpect(jsonPath("$.finishedAt").value(NOW.minusSeconds(30).toString()))
                .andExpect(jsonPath("$.receivedAt").value(NOW.toString()))
                .andExpect(jsonPath("$.score").value(61.5))
                .andExpect(jsonPath("$.counts.pass").value(2))
                .andExpect(jsonPath("$.counts.fail").value(1))
                .andExpect(jsonPath("$.counts.error").value(1))
                .andExpect(jsonPath("$.counts.notApplicable").value(1));

        getJson("/api/v1/hosts/{id}", agent.hostId())
                .andExpect(jsonPath("$.latestScan.id").value(report.scanId().toString()))
                .andExpect(jsonPath("$.lastSeenAt").value(NOW.toString()));
        assertThat(jdbc.sql("SELECT count(*) FROM scan_results")
                        .query(Long.class)
                        .single())
                .isEqualTo(5);
    }

    @Test
    void scanWithNothingApplicableHasANullScore() throws Exception {
        Agent agent = enroll("machine-0001");

        submitScan(agent.token(), ScanReports.scan(NOW).result(CRONTAB, "low", "NOT_APPLICABLE"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.score").value(nullValue()));
    }

    @Test
    void resubmittingAScanIdReturnsTheStoredSummaryAndChangesNothing() throws Exception {
        Agent agent = enroll("machine-0001");
        ScanReports.Builder report = ScanReports.scan(NOW).result(ROOT_LOGIN, "high", "PASS");
        String created = submitScan(agent.token(), report)
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        clock.advance(Duration.ofMinutes(2));
        ScanReports.Builder retry =
                ScanReports.scan(NOW).scanId(report.scanId()).result(ROOT_LOGIN, "high", "FAIL");
        submitScan(agent.token(), retry)
                .andExpect(status().isOk())
                .andExpect(content().json(created, JsonCompareMode.STRICT));

        assertThat(jdbc.sql("SELECT count(*) FROM scans").query(Long.class).single())
                .isOne();
        assertThat(jdbc.sql("SELECT status FROM scan_results")
                        .query(String.class)
                        .single())
                .isEqualTo("PASS");
    }

    @Test
    void scanIdSubmittedByAnotherHostIsAConflict() throws Exception {
        Agent first = enroll("machine-0001");
        Agent second = enroll("machine-0002");
        ScanReports.Builder report = ScanReports.scan(NOW).result(ROOT_LOGIN, "high", "PASS");
        ingest(first, report);

        submitScan(second.token(), report)
                .andExpect(status().isConflict())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail")
                        .value("Scan " + report.scanId() + " was already submitted by another host."));
    }

    @Test
    void unknownOrMissingAgentTokenIsUnauthorized() throws Exception {
        enroll("machine-0001");
        ScanReports.Builder report = ScanReports.scan(NOW).result(ROOT_LOGIN, "high", "PASS");

        submitScan("dwa_" + "A".repeat(43), report).andExpect(status().isUnauthorized());
        submitScan(ENROLLMENT_TOKEN, report).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/scans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(report.toJson(json)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"));
        assertThat(jdbc.sql("SELECT count(*) FROM scans").query(Long.class).single())
                .isZero();
    }

    @Test
    void fieldViolationsAreReportedTogetherWithTheirPaths() throws Exception {
        Agent agent = enroll("machine-0001");
        Map<String, Object> tooLong = result("ssh.title_too_long", "low", "PASS");
        tooLong.put("title", "t".repeat(201));
        tooLong.put("evidence", "e".repeat(1001));
        ScanReports.Builder report = ScanReports.scan(NOW)
                .startedAt(NOW.plusSeconds(1))
                .hostname("")
                .result(ROOT_LOGIN, "high", "PASS")
                .result("NOT_a_valid_id", "high", "PASS")
                .rawResult(tooLong)
                .result(ROOT_LOGIN, "high", "FAIL");

        submitScan(agent.token(), report)
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Bad Request"))
                .andExpect(jsonPath("$.errors[*].field")
                        .value(contains(
                                "host.hostname",
                                "results[1].checkId",
                                "results[2].evidence",
                                "results[2].title",
                                "results[3].checkId",
                                "startedAt")))
                .andExpect(jsonPath("$.errors[?(@.field == 'results[3].checkId')].message")
                        .value(contains("duplicates results[0].checkId")))
                .andExpect(jsonPath("$.errors[?(@.field == 'startedAt')].message")
                        .value(contains("must not be after finishedAt")));
        assertThat(jdbc.sql("SELECT count(*) FROM scans").query(Long.class).single())
                .isZero();
    }

    @Test
    void finishedAtMayRunAtMostFiveMinutesAhead() throws Exception {
        Agent agent = enroll("machine-0001");
        Instant limit = NOW.plus(ScanReportValidator.MAX_CLOCK_SKEW);

        submitScan(agent.token(), ScanReports.scan(limit).result(ROOT_LOGIN, "high", "PASS"))
                .andExpect(status().isCreated());
        submitScan(agent.token(), ScanReports.scan(limit.plusSeconds(1)).result(ROOT_LOGIN, "high", "PASS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("finishedAt"))
                .andExpect(jsonPath("$.errors[0].message").value("must not be more than 5 minutes in the future"));
    }

    @Test
    void resultsMustContainOneToFiveHundredEntries() throws Exception {
        Agent agent = enroll("machine-0001");
        submitScan(agent.token(), ScanReports.scan(NOW))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("results"));

        ScanReports.Builder tooMany = ScanReports.scan(NOW);
        for (int i = 0; i < 501; i++) {
            tooMany.result("ssh.check_%03d".formatted(i), "low", "PASS");
        }
        submitScan(agent.token(), tooMany)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("results"));
    }

    @Test
    void unknownEnumValuesAndMistypedValuesNameTheField() throws Exception {
        Agent agent = enroll("machine-0001");
        ScanReports.Builder badSeverity =
                ScanReports.scan(NOW).result(ROOT_LOGIN, "high", "PASS").result(ASLR, "urgent", "PASS");
        submitScan(agent.token(), badSeverity)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("results[1].severity"))
                .andExpect(jsonPath("$.errors[0].message").value("must be one of [low, medium, high, critical]"));

        Map<String, Object> badTimestamp =
                ScanReports.scan(NOW).result(ROOT_LOGIN, "high", "PASS").toMap();
        badTimestamp.put("finishedAt", "yesterday");
        submitScan(agent.token(), json.writeValueAsString(badTimestamp))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("finishedAt"))
                .andExpect(jsonPath("$.errors[0].message").value("must be an RFC 3339 date-time"));
    }

    @Test
    void malformedJsonIsABadRequestWithoutParserDetails() throws Exception {
        Agent agent = enroll("machine-0001");

        submitScan(agent.token(), "{\"scanId\": ")
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("The request body is missing or is not valid JSON."))
                .andExpect(jsonPath("$.errors").doesNotExist());
    }

    @Test
    void bodiesOverOneMebibyteAreRejectedBeforeParsing() throws Exception {
        Agent agent = enroll("machine-0001");
        String oversized = "{\"agentVersion\":\"" + "a".repeat(1024 * 1024) + "\"}";

        submitScan(agent.token(), oversized)
                .andExpect(status().isContentTooLarge())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(413))
                .andExpect(jsonPath("$.detail").value("The request body must not exceed 1048576 bytes."));
    }

    @Test
    void aLateScanIsStoredWithoutMovingTheLatestScanOrOverwritingHostFacts() throws Exception {
        Agent agent = enroll("machine-0001");
        UUID newest = ingest(
                agent,
                ScanReports.scan(NOW.minusSeconds(60)).hostname("web-01-new").result(ROOT_LOGIN, "high", "PASS"));
        clock.advance(Duration.ofSeconds(30));

        ingest(
                agent,
                ScanReports.scan(NOW.minusSeconds(120))
                        .hostname("web-01-old")
                        .agentVersion("0.0.9")
                        .result(ROOT_LOGIN, "high", "FAIL"));

        getJson("/api/v1/hosts/{id}", agent.hostId())
                .andExpect(jsonPath("$.latestScan.id").value(newest.toString()))
                .andExpect(jsonPath("$.hostname").value("web-01-new"))
                .andExpect(jsonPath("$.agentVersion").value("0.1.0"))
                .andExpect(jsonPath("$.lastSeenAt").value(NOW.plusSeconds(30).toString()));
        getJson("/api/v1/hosts/{id}/scans", agent.hostId())
                .andExpect(jsonPath("$[*].score").value(contains(100.0, 0.0)));
    }

    @Test
    void theCheckCatalogFollowsTheMostRecentlyReportedDefinition() throws Exception {
        Agent agent = enroll("machine-0001");
        ingest(
                agent,
                ScanReports.scan(NOW.minusSeconds(60))
                        .result(ROOT_LOGIN, "high", "PASS")
                        .result(ASLR, "high", "PASS"));

        Map<String, Object> renamed = result(ROOT_LOGIN, "critical", "PASS");
        renamed.put("title", "Root login over SSH is disabled");
        ingest(agent, ScanReports.scan(NOW).rawResult(renamed));

        getJson("/api/v1/checks")
                .andExpect(jsonPath("$[*].id").value(containsInAnyOrder(ROOT_LOGIN, ASLR)))
                .andExpect(jsonPath("$[?(@.id == '%s')].title".formatted(ROOT_LOGIN))
                        .value(contains("Root login over SSH is disabled")))
                .andExpect(jsonPath("$[?(@.id == '%s')].severity".formatted(ROOT_LOGIN))
                        .value(contains("critical")));
    }

    private static Map<String, Object> result(String checkId, String severity, String status) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checkId", checkId);
        result.put("title", "Title of " + checkId);
        result.put("category", "ssh");
        result.put("severity", severity);
        result.put("status", status);
        return result;
    }
}
