package io.github.tahausama210.driftwatch.fleet;

import io.github.tahausama210.driftwatch.checks.Severity;
import io.github.tahausama210.driftwatch.drift.HostDriftCounters;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Per-host gauges, recomputed on the same schedule as {@link FleetMetrics} from one query:
 *
 * <ul>
 *   <li>{@code driftwatch_host_score{host_id,hostname}}: the latest scan's score; no series while
 *       the host has no scored scan.
 *   <li>{@code driftwatch_host_last_seen_timestamp_seconds{host_id,hostname}}: last contact, Unix
 *       seconds; present for every enrolled host.
 *   <li>{@code driftwatch_host_failing_checks{host_id,hostname,severity}}: FAIL and ERROR results of
 *       the latest scan, one series per severity (0 when none); absent until the first scan.
 * </ul>
 *
 * <p>The same refresh brings the per-host drift counters ({@link HostDriftCounters}) in line with
 * the enrolled hosts, so every host has its three series at zero before its first drift event.
 *
 * <p>{@code host_id} identifies the host; {@code hostname} is for people. A renamed host's series
 * move to the new label set on the next refresh, and a decommissioned host's series disappear. With
 * the drift counters that is at most 1 + 1 + 4 + 3 = 9 series per host.
 */
@Component
class HostMetrics {

    private final FleetQueries fleet;
    private final HostDriftCounters driftCounters;
    private final GaugeFamily scores;
    private final GaugeFamily lastSeen;
    private final GaugeFamily failingChecks;

    HostMetrics(FleetQueries fleet, HostDriftCounters driftCounters, MeterRegistry registry) {
        this.fleet = fleet;
        this.driftCounters = driftCounters;
        this.scores = new GaugeFamily(registry, "driftwatch.host.score", "Compliance score of the host's latest scan");
        this.lastSeen = new GaugeFamily(
                registry,
                "driftwatch.host.last.seen.timestamp.seconds",
                "Last enrollment or scan submission of the host, as Unix seconds");
        this.failingChecks = new GaugeFamily(
                registry,
                "driftwatch.host.failing.checks",
                "Checks that are FAIL or ERROR on the host's latest scan, by severity");
    }

    /** Synchronised because tests also call it directly while the scheduler may run it. */
    @Scheduled(fixedDelayString = "${driftwatch.metrics-refresh-interval}")
    synchronized void refresh() {
        List<FleetQueries.HostSnapshot> hosts = fleet.hostSnapshots();
        Map<Tags, Double> scoreValues = new HashMap<>();
        Map<Tags, Double> lastSeenValues = new HashMap<>();
        Map<Tags, Double> failingValues = new HashMap<>();
        Map<UUID, String> hostnames = new HashMap<>();
        for (FleetQueries.HostSnapshot host : hosts) {
            hostnames.put(host.hostId(), host.hostname());
            Tags tags = Tags.of("host_id", host.hostId().toString(), "hostname", host.hostname());
            lastSeenValues.put(tags, host.lastSeenAt().toEpochMilli() / 1000.0);
            if (host.score() != null) {
                scoreValues.put(tags, host.score().doubleValue());
            }
            if (host.scanned()) {
                for (Severity severity : Severity.values()) {
                    failingValues.put(tags.and("severity", severity.wireValue()), (double)
                            host.failingBySeverity().getOrDefault(severity, 0));
                }
            }
        }
        scores.replaceAll(scoreValues);
        lastSeen.replaceAll(lastSeenValues);
        failingChecks.replaceAll(failingValues);
        driftCounters.synchronize(hostnames);
    }
}
