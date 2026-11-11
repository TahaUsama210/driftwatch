package io.github.tahausama210.driftwatch.fleet;

import static io.github.tahausama210.driftwatch.support.ScanReports.ASLR;
import static io.github.tahausama210.driftwatch.support.ScanReports.CRONTAB;
import static io.github.tahausama210.driftwatch.support.ScanReports.IP_FORWARDING;
import static io.github.tahausama210.driftwatch.support.ScanReports.ROOT_LOGIN;
import static io.github.tahausama210.driftwatch.support.ScanReports.UID0_ONLY_ROOT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.tahausama210.driftwatch.support.IntegrationTest;
import io.github.tahausama210.driftwatch.support.ScanReports;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The per-host series as Prometheus scrapes them from {@code /actuator/prometheus}. */
class HostMetricsIT extends IntegrationTest {

    @Autowired
    private HostMetrics hostMetrics;

    @Autowired
    private MeterRegistry registry;

    @Test
    void eachHostGetsItsScoreLastSeenAndFailingChecksBySeverity() throws Exception {
        Agent web = enroll("machine-000a", "web-01");
        ingest(
                web,
                ScanReports.scan(NOW)
                        .result(UID0_ONLY_ROOT, "critical", "FAIL")
                        .result(ROOT_LOGIN, "high", "ERROR")
                        .result(ASLR, "high", "FAIL")
                        .result(IP_FORWARDING, "medium", "PASS")
                        .result(CRONTAB, "low", "NOT_APPLICABLE"));

        hostMetrics.refresh();

        String labels = "host_id=\"%s\",hostname=\"web-01\"".formatted(web.hostId());
        // Weights: critical 5 + high 3 + high 3 failing, medium 2 passing: 100 * 2 / 13 = 15.4.
        assertThat(scrape())
                .contains("driftwatch_host_score{" + labels + "} 15.4")
                .contains(
                        "driftwatch_host_last_seen_timestamp_seconds{" + labels + "} " + (double) NOW.getEpochSecond())
                .contains("driftwatch_host_failing_checks{" + labels + ",severity=\"critical\"} 1.0")
                .contains("driftwatch_host_failing_checks{" + labels + ",severity=\"high\"} 2.0")
                .contains("driftwatch_host_failing_checks{" + labels + ",severity=\"medium\"} 0.0")
                .contains("driftwatch_host_failing_checks{" + labels + ",severity=\"low\"} 0.0");
    }

    @Test
    void aHostWithoutAScoreHasNoScoreSeriesAndAnUnscannedHostNoFailingSeries() throws Exception {
        Agent notApplicable = enroll("machine-000a", "na-01");
        ingest(notApplicable, ScanReports.scan(NOW).result(CRONTAB, "low", "NOT_APPLICABLE"));
        Agent unscanned = enroll("machine-000b", "new-01");

        hostMetrics.refresh();

        String metrics = scrape();
        assertThat(metrics)
                .doesNotContain("driftwatch_host_score{host_id=\"" + notApplicable.hostId())
                .contains("driftwatch_host_failing_checks{host_id=\"" + notApplicable.hostId()
                        + "\",hostname=\"na-01\",severity=\"low\"} 0.0")
                .contains("driftwatch_host_last_seen_timestamp_seconds{host_id=\"" + unscanned.hostId())
                .doesNotContain("driftwatch_host_failing_checks{host_id=\"" + unscanned.hostId())
                .doesNotContain("driftwatch_host_score{host_id=\"" + unscanned.hostId());
    }

    @Test
    void valuesFollowTheLatestScanAndARenameMovesTheSeries() throws Exception {
        Agent agent = enroll("machine-000a", "web-01");
        ingest(agent, ScanReports.scan(NOW).result(ROOT_LOGIN, "high", "FAIL"));
        hostMetrics.refresh();
        clock.advance(Duration.ofMinutes(1));

        ingest(
                agent,
                ScanReports.scan(clock.instant()).hostname("web-01-renamed").result(ROOT_LOGIN, "high", "PASS"));
        hostMetrics.refresh();

        String metrics = scrape();
        assertThat(metrics)
                .contains("driftwatch_host_score{host_id=\"%s\",hostname=\"web-01-renamed\"} 100.0"
                        .formatted(agent.hostId()))
                .doesNotContain("hostname=\"web-01\"");
    }

    @Test
    void driftEventsAreCountedPerHostAndDirection() throws Exception {
        Agent drifting = enroll("machine-000a", "alpine-drifting");
        Agent steady = enroll("machine-000b", "steady-01");
        ingest(drifting, scanAt(0, "PASS", "FAIL"));
        ingest(drifting, scanAt(1, "FAIL", "PASS")); // root login REGRESSED, uid0 RESOLVED
        ingest(drifting, scanAt(2, "PASS", "PASS")); // root login RESOLVED
        ingest(steady, scanAt(0, "PASS", "PASS"));
        ingest(steady, scanAt(1, "PASS", "PASS"));

        String drifted = driftLabels(drifting, "alpine-drifting");
        String still = driftLabels(steady, "steady-01");
        assertThat(scrape())
                .contains("driftwatch_host_drift_events_total{" + drifted.formatted("REGRESSED") + "} 1.0")
                .contains("driftwatch_host_drift_events_total{" + drifted.formatted("RESOLVED") + "} 2.0")
                .contains("driftwatch_host_drift_events_total{" + drifted.formatted("CHANGED") + "} 0.0")
                .contains("driftwatch_host_drift_events_total{" + still.formatted("REGRESSED") + "} 0.0")
                .contains("driftwatch_drift_events_total{direction=\"REGRESSED\"}");
    }

    /** increase() misses a series' first increment unless it was scraped at zero before. */
    @Test
    void everyDirectionIsAtZeroFromEnrollmentBeforeAnyDrift() throws Exception {
        Agent agent = enroll("machine-000a", "web-01");

        String labels = driftLabels(agent, "web-01");
        assertThat(scrape())
                .contains("driftwatch_host_drift_events_total{" + labels.formatted("REGRESSED") + "} 0.0")
                .contains("driftwatch_host_drift_events_total{" + labels.formatted("RESOLVED") + "} 0.0")
                .contains("driftwatch_host_drift_events_total{" + labels.formatted("CHANGED") + "} 0.0");
    }

    /** After an API restart the registry is empty; the first refresh puts every host back at zero. */
    @Test
    void theRefreshRegistersMissingDriftSeriesAndDropsStaleOnes() throws Exception {
        Agent agent = enroll("machine-000a", "web-01");
        registry.find("driftwatch.host.drift.events").meters().forEach(registry::remove);
        jdbc.sql("UPDATE hosts SET hostname = 'web-01-renamed' WHERE id = :id")
                .param("id", agent.hostId())
                .update();

        hostMetrics.refresh();

        assertThat(scrape())
                .contains("driftwatch_host_drift_events_total{"
                        + driftLabels(agent, "web-01-renamed").formatted("CHANGED") + "} 0.0")
                .doesNotContain("hostname=\"web-01\"");
    }

    private static String driftLabels(Agent agent, String hostname) {
        return "direction=\"%s\",host_id=\"" + agent.hostId() + "\",hostname=\"" + hostname + "\"";
    }

    @Test
    void aDecommissionedHostLosesAllItsSeries() throws Exception {
        Agent doomed = enroll("machine-000a", "doomed-01");
        Agent kept = enroll("machine-000b", "kept-01");
        for (Agent agent : List.of(doomed, kept)) {
            ingest(agent, scanAt(0, "PASS", "PASS"));
            ingest(agent, scanAt(1, "FAIL", "PASS"));
        }
        hostMetrics.refresh();
        assertThat(scrape()).contains("host_id=\"" + doomed.hostId());

        mvc.perform(delete("/api/v1/hosts/{id}", doomed.hostId()).with(asAdmin()))
                .andExpect(status().isNoContent());

        assertThat(scrape())
                .as("the drift counter goes with the decommission")
                .doesNotContain(
                        "driftwatch_host_drift_events_total{direction=\"REGRESSED\",host_id=\"" + doomed.hostId())
                .contains("driftwatch_host_drift_events_total{direction=\"REGRESSED\",host_id=\"" + kept.hostId());
        hostMetrics.refresh();
        assertThat(scrape())
                .as("the gauges go on the next refresh")
                .doesNotContain(doomed.hostId().toString())
                .contains("driftwatch_host_score{host_id=\"" + kept.hostId());
    }

    /** A scan {@code minutes} after NOW with the root-login and uid0 checks in the given statuses. */
    private ScanReports.Builder scanAt(int minutes, String rootLogin, String uid0) {
        clock.set(NOW.plus(Duration.ofMinutes(minutes)));
        return ScanReports.scan(clock.instant())
                .result(ROOT_LOGIN, "high", rootLogin)
                .result(UID0_ONLY_ROOT, "critical", uid0);
    }

    private String scrape() throws Exception {
        return mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
