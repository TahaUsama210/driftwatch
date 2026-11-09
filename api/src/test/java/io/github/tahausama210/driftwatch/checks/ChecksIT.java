package io.github.tahausama210.driftwatch.checks;

import static io.github.tahausama210.driftwatch.support.ScanReports.ASLR;
import static io.github.tahausama210.driftwatch.support.ScanReports.CRONTAB;
import static io.github.tahausama210.driftwatch.support.ScanReports.IP_FORWARDING;
import static io.github.tahausama210.driftwatch.support.ScanReports.ROOT_LOGIN;
import static io.github.tahausama210.driftwatch.support.ScanReports.UID0_ONLY_ROOT;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.tahausama210.driftwatch.support.IntegrationTest;
import io.github.tahausama210.driftwatch.support.ScanReports;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChecksIT extends IntegrationTest {

    @Test
    void emptyCatalog() throws Exception {
        getJson("/api/v1/checks").andExpect(status().isOk()).andExpect(jsonPath("$", empty()));
    }

    @Test
    void catalogCountsOnlyEachHostsLatestScanAndSortsByFleetImpact() throws Exception {
        Agent alpha = enroll("machine-000a", "alpha");
        Agent bravo = enroll("machine-000b", "bravo");
        // alpha's older scan fails everything; only its latest scan may count.
        ingest(
                alpha,
                ScanReports.scan(NOW.minusSeconds(60))
                        .result(ROOT_LOGIN, "high", "FAIL")
                        .result(CRONTAB, "low", "FAIL"));
        ingest(
                alpha,
                ScanReports.scan(NOW)
                        .result(ROOT_LOGIN, "high", "PASS")
                        .result(UID0_ONLY_ROOT, "critical", "FAIL")
                        .result(IP_FORWARDING, "medium", "ERROR")
                        .result(CRONTAB, "low", "NOT_APPLICABLE"));
        ingest(
                bravo,
                ScanReports.scan(NOW)
                        .result(ROOT_LOGIN, "high", "FAIL")
                        .result(IP_FORWARDING, "medium", "FAIL")
                        .result(ASLR, "high", "PASS"));

        getJson("/api/v1/checks")
                // failing hosts desc (2, 1, 1, 0, 0), then severity weight desc, then id
                .andExpect(
                        jsonPath("$[*].id").value(contains(IP_FORWARDING, UID0_ONLY_ROOT, ROOT_LOGIN, ASLR, CRONTAB)))
                .andExpect(jsonPath("$[0].counts.fail").value(1))
                .andExpect(jsonPath("$[0].counts.error").value(1))
                .andExpect(jsonPath("$[2].counts.pass").value(1))
                .andExpect(jsonPath("$[2].counts.fail").value(1))
                .andExpect(jsonPath("$[4].counts.fail").value(0))
                .andExpect(jsonPath("$[4].counts.notApplicable").value(1))
                .andExpect(jsonPath("$[3].category").value("kernel"))
                .andExpect(jsonPath("$[3].severity").value("high"));
    }

    @Test
    void checkDetailListsFailingHostsFirstThenByHostname() throws Exception {
        Agent alpha = enroll("machine-000a", "alpha");
        Agent bravo = enroll("machine-000b", "bravo");
        Agent charlie = enroll("machine-000c", "charlie");
        Agent delta = enroll("machine-000d", "delta");
        ingest(alpha, ScanReports.scan(NOW).result(ROOT_LOGIN, "high", "PASS"));
        UUID bravoScan = ingest(
                bravo, ScanReports.scan(NOW.minusSeconds(5)).result(ROOT_LOGIN, "high", "FAIL", "PermitRootLogin yes"));
        ingest(charlie, ScanReports.scan(NOW).result(ROOT_LOGIN, "high", "ERROR", "permission denied"));
        // delta's latest scan does not include the check at all.
        ingest(delta, ScanReports.scan(NOW).result(ASLR, "high", "PASS"));

        getJson("/api/v1/checks/{id}", ROOT_LOGIN)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(ROOT_LOGIN))
                .andExpect(jsonPath("$.title").value("Title of " + ROOT_LOGIN))
                .andExpect(jsonPath("$.counts.pass").value(1))
                .andExpect(jsonPath("$.counts.fail").value(1))
                .andExpect(jsonPath("$.counts.error").value(1))
                .andExpect(jsonPath("$.hosts[*].hostname").value(contains("bravo", "charlie", "alpha")))
                .andExpect(jsonPath("$.hosts[0].hostId").value(bravo.hostId().toString()))
                .andExpect(jsonPath("$.hosts[0].status").value("FAIL"))
                .andExpect(jsonPath("$.hosts[0].evidence").value("PermitRootLogin yes"))
                .andExpect(jsonPath("$.hosts[0].expected").value("expected value"))
                .andExpect(jsonPath("$.hosts[0].scanId").value(bravoScan.toString()))
                .andExpect(jsonPath("$.hosts[0].finishedAt")
                        .value(NOW.minusSeconds(5).toString()))
                .andExpect(hasPropertyNames("$", "id", "title", "category", "severity", "counts", "hosts"))
                .andExpect(hasPropertyNames("$.counts", "pass", "fail", "error", "notApplicable"))
                .andExpect(hasPropertyNames(
                        "$.hosts[0]",
                        "hostId",
                        "hostname",
                        "status",
                        "expected",
                        "actual",
                        "evidence",
                        "scanId",
                        "finishedAt"));
    }

    @Test
    void unknownCheckIsNotFound() throws Exception {
        getJson("/api/v1/checks/{id}", "ssh.does_not_exist")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Check ssh.does_not_exist does not exist."));
    }
}
