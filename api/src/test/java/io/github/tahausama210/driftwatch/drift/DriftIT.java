package io.github.tahausama210.driftwatch.drift;

import static io.github.tahausama210.driftwatch.support.ScanReports.ASLR;
import static io.github.tahausama210.driftwatch.support.ScanReports.CRONTAB;
import static io.github.tahausama210.driftwatch.support.ScanReports.IP_FORWARDING;
import static io.github.tahausama210.driftwatch.support.ScanReports.ROOT_LOGIN;
import static io.github.tahausama210.driftwatch.support.ScanReports.UID0_ONLY_ROOT;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.tahausama210.driftwatch.support.IntegrationTest;
import io.github.tahausama210.driftwatch.support.ScanReports;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DriftIT extends IntegrationTest {

    private static final Instant T1 = NOW.minusSeconds(300);
    private static final Instant T2 = NOW.minusSeconds(200);
    private static final Instant T3 = NOW.minusSeconds(100);

    @Test
    void theFirstScanOfAHostIsItsBaselineAndRecordsNoEvents() throws Exception {
        Agent agent = enroll("machine-0001");
        ingest(agent, ScanReports.scan(T1).result(ROOT_LOGIN, "high", "FAIL"));

        getJson("/api/v1/events").andExpect(status().isOk()).andExpect(jsonPath("$", empty()));
    }

    @Test
    void consecutiveScansRecordOneEventPerChangedStatus() throws Exception {
        Agent agent = enroll("machine-0001", "web-01");
        ingest(
                agent,
                ScanReports.scan(T1)
                        .result(ROOT_LOGIN, "high", "PASS", "PermitRootLogin no")
                        .result(UID0_ONLY_ROOT, "critical", "NOT_APPLICABLE")
                        .result(IP_FORWARDING, "medium", "FAIL", "net.ipv4.ip_forward = 1")
                        .result(ASLR, "high", "FAIL", "randomize_va_space = 1")
                        .result(CRONTAB, "low", "PASS", "mode 0600")
                        .result("ssh.only_in_first_scan", "low", "PASS"));
        UUID second = ingest(
                agent,
                ScanReports.scan(T2)
                        .result(ROOT_LOGIN, "high", "FAIL", "PermitRootLogin yes")
                        .result(UID0_ONLY_ROOT, "critical", "ERROR", "permission denied")
                        .result(IP_FORWARDING, "medium", "PASS", "net.ipv4.ip_forward = 0")
                        .result(ASLR, "high", "ERROR", "parse error")
                        // Same status, different evidence: not drift.
                        .result(CRONTAB, "low", "PASS", "mode 0400")
                        .result("ssh.only_in_second_scan", "low", "FAIL"));

        getJson("/api/v1/events?hostId={id}", agent.hostId())
                .andExpect(jsonPath("$", hasSize(4)))
                .andExpect(jsonPath("$[*].scanId").value(contains(nTimes(second.toString(), 4))))
                .andExpect(jsonPath("$[*].detectedAt").value(contains(nTimes(T2.toString(), 4))))
                .andExpect(jsonPath("$[*].hostname").value(contains(nTimes("web-01", 4))));
        getJson("/api/v1/events?hostId={id}&direction=REGRESSED", agent.hostId())
                .andExpect(jsonPath("$[*].checkId").value(containsInAnyOrder(ROOT_LOGIN, UID0_ONLY_ROOT)));
        getJson("/api/v1/events?direction=RESOLVED")
                .andExpect(jsonPath("$[0].checkId").value(IP_FORWARDING))
                .andExpect(jsonPath("$[0].fromStatus").value("FAIL"))
                .andExpect(jsonPath("$[0].toStatus").value("PASS"))
                .andExpect(jsonPath("$[0].fromEvidence").value("net.ipv4.ip_forward = 1"))
                .andExpect(jsonPath("$[0].toEvidence").value("net.ipv4.ip_forward = 0"))
                .andExpect(jsonPath("$[0].severity").value("medium"))
                .andExpect(jsonPath("$[0].category").value("network"))
                .andExpect(hasPropertyNames(
                        "$[0]",
                        "id",
                        "hostId",
                        "hostname",
                        "scanId",
                        "detectedAt",
                        "checkId",
                        "title",
                        "category",
                        "severity",
                        "fromStatus",
                        "toStatus",
                        "fromEvidence",
                        "toEvidence",
                        "direction"));
        getJson("/api/v1/events?direction=CHANGED")
                .andExpect(jsonPath("$[*].checkId").value(contains(ASLR)));
    }

    @Test
    void aLateScanIsDiffedAgainstItsPredecessorAndItsSuccessorIsReDerived() throws Exception {
        Agent agent = enroll("machine-0001");
        UUID first = ingest(agent, ScanReports.scan(T1).result(ROOT_LOGIN, "high", "PASS"));
        UUID third = ingest(agent, ScanReports.scan(T3).result(ROOT_LOGIN, "high", "PASS"));
        getJson("/api/v1/events").andExpect(jsonPath("$", empty()));

        UUID second = ingest(agent, ScanReports.scan(T2).result(ROOT_LOGIN, "high", "FAIL"));

        getJson("/api/v1/events")
                .andExpect(jsonPath("$[*].scanId").value(contains(third.toString(), second.toString())))
                .andExpect(jsonPath("$[*].direction").value(contains("RESOLVED", "REGRESSED")));
        getJson("/api/v1/hosts/{id}/drift", agent.hostId())
                .andExpect(jsonPath("$.fromScan.id").value(second.toString()))
                .andExpect(jsonPath("$.toScan.id").value(third.toString()));

        // Older than every stored scan: it becomes the baseline and the old first scan is re-derived.
        UUID zeroth = ingest(agent, ScanReports.scan(T1.minusSeconds(100)).result(ROOT_LOGIN, "high", "ERROR"));
        getJson("/api/v1/events")
                .andExpect(
                        jsonPath("$[*].scanId").value(contains(third.toString(), second.toString(), first.toString())))
                .andExpect(jsonPath("$[2].fromStatus").value("ERROR"))
                .andExpect(jsonPath("$[2].direction").value("RESOLVED"));
        getJson("/api/v1/events?direction=CHANGED").andExpect(jsonPath("$", empty()));
        getJson("/api/v1/hosts/{id}/scans", agent.hostId())
                .andExpect(jsonPath("$[*].id")
                        .value(contains(third.toString(), second.toString(), first.toString(), zeroth.toString())));
    }

    @Test
    void driftReportDefaultsToTheLatestScanAndItsPredecessorInReportOrder() throws Exception {
        Agent agent = enroll("machine-0001");
        ingest(
                agent,
                ScanReports.scan(T1)
                        .result(ROOT_LOGIN, "high", "PASS")
                        .result(UID0_ONLY_ROOT, "critical", "PASS")
                        .result(IP_FORWARDING, "medium", "FAIL")
                        .result(ASLR, "high", "FAIL")
                        .result(CRONTAB, "low", "PASS"));
        UUID middle = ingest(agent, ScanReports.scan(T2).result(ROOT_LOGIN, "high", "PASS"));
        ingest(
                agent,
                ScanReports.scan(T3)
                        .result(ROOT_LOGIN, "high", "FAIL")
                        .result(UID0_ONLY_ROOT, "critical", "FAIL")
                        .result(IP_FORWARDING, "medium", "PASS")
                        .result(ASLR, "high", "ERROR")
                        .result(CRONTAB, "low", "NOT_APPLICABLE"));

        // The default compares with the immediately preceding scan, which only has ROOT_LOGIN.
        getJson("/api/v1/hosts/{id}/drift", agent.hostId())
                .andExpect(jsonPath("$.fromScan.id").value(middle.toString()))
                .andExpect(jsonPath("$.changes[*].checkId").value(contains(ROOT_LOGIN)));
    }

    @Test
    void driftReportBetweenExplicitScansOrdersRegressionsFirstThenSeverity() throws Exception {
        Agent agent = enroll("machine-0001");
        UUID from = ingest(
                agent,
                ScanReports.scan(T1)
                        .result(ROOT_LOGIN, "high", "PASS")
                        .result(UID0_ONLY_ROOT, "critical", "PASS")
                        .result(IP_FORWARDING, "medium", "FAIL")
                        .result(ASLR, "high", "FAIL")
                        .result(CRONTAB, "low", "PASS"));
        ingest(agent, ScanReports.scan(T2).result(ROOT_LOGIN, "high", "PASS"));
        UUID to = ingest(
                agent,
                ScanReports.scan(T3)
                        .result(ROOT_LOGIN, "high", "FAIL")
                        .result(UID0_ONLY_ROOT, "critical", "FAIL")
                        .result(IP_FORWARDING, "medium", "PASS")
                        .result(ASLR, "high", "ERROR")
                        .result(CRONTAB, "low", "NOT_APPLICABLE"));

        getJson("/api/v1/hosts/{id}/drift?from={from}&to={to}", agent.hostId(), from, to)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hostId").value(agent.hostId().toString()))
                .andExpect(jsonPath("$.fromScan.id").value(from.toString()))
                .andExpect(jsonPath("$.toScan.id").value(to.toString()))
                .andExpect(jsonPath("$.changes[*].checkId")
                        .value(contains(UID0_ONLY_ROOT, ROOT_LOGIN, IP_FORWARDING, ASLR, CRONTAB)))
                .andExpect(jsonPath("$.changes[*].direction")
                        .value(contains("REGRESSED", "REGRESSED", "RESOLVED", "CHANGED", "CHANGED")));

        // Reversed, the same pair reads the other way round.
        getJson("/api/v1/hosts/{id}/drift?from={from}&to={to}", agent.hostId(), to, from)
                .andExpect(jsonPath("$.changes[*].direction")
                        .value(contains("REGRESSED", "RESOLVED", "RESOLVED", "CHANGED", "CHANGED")));
    }

    @Test
    void driftReportForAHostWithFewerThanTwoScansHasNoFromScan() throws Exception {
        Agent agent = enroll("machine-0001");
        getJson("/api/v1/hosts/{id}/drift", agent.hostId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromScan").value(nullValue()))
                .andExpect(jsonPath("$.toScan").value(nullValue()))
                .andExpect(jsonPath("$.changes", empty()));

        UUID only = ingest(agent, ScanReports.scan(T1).result(ROOT_LOGIN, "high", "PASS"));
        getJson("/api/v1/hosts/{id}/drift", agent.hostId())
                .andExpect(jsonPath("$.fromScan").value(nullValue()))
                .andExpect(jsonPath("$.toScan.id").value(only.toString()))
                .andExpect(jsonPath("$.changes", empty()));
    }

    @Test
    void driftReportOnlyComparesScansOfTheRequestedHost() throws Exception {
        Agent agent = enroll("machine-0001");
        Agent other = enroll("machine-0002");
        ingest(agent, ScanReports.scan(T1).result(ROOT_LOGIN, "high", "PASS"));
        UUID foreign = ingest(other, ScanReports.scan(T1).result(ROOT_LOGIN, "high", "PASS"));

        getJson("/api/v1/hosts/{id}/drift?from={from}", agent.hostId(), foreign)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
        getJson("/api/v1/hosts/{id}/drift", UUID.randomUUID()).andExpect(status().isNotFound());
        getJson("/api/v1/hosts/{id}/drift?to=not-a-uuid", agent.hostId())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("to"))
                .andExpect(jsonPath("$.errors[0].message").value("must be a UUID"));
    }

    @Test
    void eventFeedIsNewestFirstAndHonoursTheLimit() throws Exception {
        Agent agent = enroll("machine-0001");
        String[] statuses = {"PASS", "FAIL", "PASS", "FAIL"};
        for (int i = 0; i < statuses.length; i++) {
            ingest(agent, ScanReports.scan(T1.plusSeconds(i * 10L)).result(ROOT_LOGIN, "high", statuses[i]));
        }

        getJson("/api/v1/events")
                .andExpect(jsonPath("$[*].direction").value(contains("REGRESSED", "RESOLVED", "REGRESSED")));
        getJson("/api/v1/events?limit=2")
                .andExpect(jsonPath("$[*].detectedAt")
                        .value(contains(
                                T1.plusSeconds(30).toString(),
                                T1.plusSeconds(20).toString())));
        getJson("/api/v1/events?hostId={id}", UUID.randomUUID()).andExpect(jsonPath("$", empty()));
    }

    @Test
    void eventFeedRejectsInvalidFilters() throws Exception {
        getJson("/api/v1/events?direction=WORSE")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("direction"))
                .andExpect(jsonPath("$.errors[0].message").value("must be one of [REGRESSED, RESOLVED, CHANGED]"));
        getJson("/api/v1/events?limit=201")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("limit"));
        getJson("/api/v1/events?limit=0").andExpect(status().isBadRequest());
    }

    private static String[] nTimes(String value, int times) {
        String[] values = new String[times];
        Arrays.fill(values, value);
        return values;
    }
}
