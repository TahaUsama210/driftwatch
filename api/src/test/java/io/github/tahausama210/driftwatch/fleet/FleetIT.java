package io.github.tahausama210.driftwatch.fleet;

import static io.github.tahausama210.driftwatch.support.ScanReports.ASLR;
import static io.github.tahausama210.driftwatch.support.ScanReports.CRONTAB;
import static io.github.tahausama210.driftwatch.support.ScanReports.IP_FORWARDING;
import static io.github.tahausama210.driftwatch.support.ScanReports.ROOT_LOGIN;
import static io.github.tahausama210.driftwatch.support.ScanReports.UID0_ONLY_ROOT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.tahausama210.driftwatch.support.IntegrationTest;
import io.github.tahausama210.driftwatch.support.ScanReports;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class FleetIT extends IntegrationTest {

    @Autowired
    private FleetMetrics fleetMetrics;

    @Test
    void summaryOfAnEmptyFleet() throws Exception {
        getJson("/api/v1/fleet/summary")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hostCount").value(0))
                .andExpect(jsonPath("$.onlineCount").value(0))
                .andExpect(jsonPath("$.staleCount").value(0))
                .andExpect(jsonPath("$.averageScore").value(nullValue()))
                .andExpect(jsonPath("$.hostsWithCriticalFailures").value(0))
                .andExpect(jsonPath("$.topFailingChecks", empty()))
                .andExpect(jsonPath("$.lastScanAt").value(nullValue()));
    }

    @Test
    void summaryAggregatesEachHostsLatestScan() throws Exception {
        givenMixedFleet();

        getJson("/api/v1/fleet/summary")
                .andExpect(jsonPath("$.hostCount").value(5))
                .andExpect(jsonPath("$.onlineCount").value(4))
                .andExpect(jsonPath("$.staleCount").value(1))
                // (80.0 + 37.5 + 0.0) / 3 = 39.1666...; the null-score and scan-less hosts are left out.
                .andExpect(jsonPath("$.averageScore").value(39.2))
                .andExpect(jsonPath("$.hostsWithCriticalFailures").value(1))
                .andExpect(jsonPath("$.topFailingChecks[*].checkId").value(contains(IP_FORWARDING, UID0_ONLY_ROOT)))
                .andExpect(jsonPath("$.topFailingChecks[*].failingHosts").value(contains(2, 1)))
                .andExpect(jsonPath("$.topFailingChecks[1].severity").value("critical"))
                .andExpect(jsonPath("$.lastScanAt").value(NOW.minusSeconds(10).toString()));
    }

    @Test
    void summaryListsAtMostFiveFailingChecks() throws Exception {
        Agent agent = enroll("machine-0001");
        ScanReports.Builder report = ScanReports.scan(NOW);
        for (int i = 0; i < 7; i++) {
            report.result("ssh.failing_%d".formatted(i), "low", "FAIL");
        }
        ingest(agent, report);

        getJson("/api/v1/fleet/summary")
                .andExpect(jsonPath("$.topFailingChecks", hasSize(5)))
                .andExpect(jsonPath("$.topFailingChecks[0].checkId").value("ssh.failing_0"));
    }

    @Test
    void trendCarriesEachHostsLatestScanForwardIntoEveryBucket() throws Exception {
        Agent a = enroll("machine-000a");
        Agent b = enroll("machine-000b");
        Agent c = enroll("machine-000c");
        Instant tenOClock = Instant.parse("2026-11-03T10:00:00Z");
        ingest(
                a,
                ScanReports.scan(tenOClock.plusSeconds(15 * 60))
                        .result(ROOT_LOGIN, "high", "PASS")
                        .result(ASLR, "high", "FAIL"));
        ingest(a, ScanReports.scan(tenOClock.plusSeconds(105 * 60)).result(ROOT_LOGIN, "high", "PASS"));
        ingest(b, ScanReports.scan(tenOClock.plusSeconds(70 * 60)).result(ROOT_LOGIN, "high", "FAIL"));
        // Exactly on a bucket boundary: belongs to the 12:00 bucket, not the 11:00 one.
        ingest(c, ScanReports.scan(NOW).result(ROOT_LOGIN, "high", "PASS"));
        clock.set(NOW.plus(Duration.ofMinutes(20)));

        getJson("/api/v1/fleet/trend?hours=4&bucketMinutes=60")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bucketMinutes").value(60))
                .andExpect(jsonPath("$.points[*].bucketStart")
                        .value(contains(
                                "2026-11-03T09:00:00Z",
                                "2026-11-03T10:00:00Z",
                                "2026-11-03T11:00:00Z",
                                "2026-11-03T12:00:00Z")))
                .andExpect(jsonPath("$.points[0].averageScore").value(nullValue()))
                .andExpect(jsonPath("$.points[*].hostCount").value(contains(0, 1, 2, 3)))
                // 10:00 a=50.0; 11:00 a=100.0, b=0.0; 12:00 adds c=100.0 -> 66.666...
                .andExpect(jsonPath("$.points[1].averageScore").value(50.0))
                .andExpect(jsonPath("$.points[2].averageScore").value(50.0))
                .andExpect(jsonPath("$.points[3].averageScore").value(66.7));
    }

    @Test
    void trendDefaultsToTwentyFourHourlyBucketsEndingWithTheCurrentOne() throws Exception {
        clock.set(NOW.plusSeconds(59 * 60));

        getJson("/api/v1/fleet/trend")
                .andExpect(jsonPath("$.bucketMinutes").value(60))
                .andExpect(jsonPath("$.points", hasSize(24)))
                .andExpect(jsonPath("$.points[0].bucketStart").value("2026-11-02T13:00:00Z"))
                .andExpect(jsonPath("$.points[23].bucketStart").value("2026-11-03T12:00:00Z"))
                .andExpect(jsonPath("$.points[23].hostCount").value(0));
    }

    @Test
    void trendIsCappedAtFiveHundredPoints() throws Exception {
        getJson("/api/v1/fleet/trend?hours=500&bucketMinutes=60").andExpect(jsonPath("$.points", hasSize(500)));
        getJson("/api/v1/fleet/trend?hours=720&bucketMinutes=60")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("bucketMinutes"));
    }

    @Test
    void trendParametersAreRangeChecked() throws Exception {
        getJson("/api/v1/fleet/trend?hours=0")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("hours"));
        getJson("/api/v1/fleet/trend?bucketMinutes=1441")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("bucketMinutes"));
        getJson("/api/v1/fleet/trend?hours=many")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("must be an integer"));
    }

    @Test
    void fleetGaugesAreRefreshedFromTheDatabase() throws Exception {
        givenMixedFleet();

        fleetMetrics.refresh();

        String metrics = mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(metrics)
                .contains("driftwatch_hosts{status=\"online\"} 4.0")
                .contains("driftwatch_hosts{status=\"stale\"} 1.0")
                .contains("driftwatch_fleet_average_score 39.2")
                .contains("driftwatch_scans_ingested_total{outcome=\"created\"}")
                .contains("driftwatch_scans_ingested_total{outcome=\"rejected\"}")
                .contains("driftwatch_drift_events_total{direction=\"REGRESSED\"}")
                .contains("driftwatch_scan_ingest_duration_seconds_bucket");
    }

    /**
     * Five hosts: a (80.0, fails a medium check), b (37.5, fails a critical check), d (0.0, stale),
     * e (latest scan has nothing applicable) and c (never scanned).
     */
    private void givenMixedFleet() throws Exception {
        clock.set(NOW.minus(Duration.ofMinutes(10)));
        Agent d = enroll("machine-000d");
        ingest(d, ScanReports.scan(clock.instant()).result(IP_FORWARDING, "medium", "ERROR"));
        clock.set(NOW);

        Agent a = enroll("machine-000a");
        Agent b = enroll("machine-000b");
        enroll("machine-000c");
        Agent e = enroll("machine-000e");
        ingest(
                a,
                ScanReports.scan(NOW.minusSeconds(30))
                        .result(ROOT_LOGIN, "high", "PASS")
                        .result(UID0_ONLY_ROOT, "critical", "PASS")
                        .result(IP_FORWARDING, "medium", "FAIL"));
        ingest(
                b,
                ScanReports.scan(NOW.minusSeconds(20))
                        .result(ROOT_LOGIN, "high", "PASS")
                        .result(UID0_ONLY_ROOT, "critical", "FAIL"));
        ingest(e, ScanReports.scan(NOW.minusSeconds(10)).result(CRONTAB, "low", "NOT_APPLICABLE"));
    }
}
