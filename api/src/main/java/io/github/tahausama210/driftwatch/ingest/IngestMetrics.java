package io.github.tahausama210.driftwatch.ingest;

import io.github.tahausama210.driftwatch.drift.DriftChange;
import io.github.tahausama210.driftwatch.drift.DriftDirection;
import io.github.tahausama210.driftwatch.drift.HostDriftCounters;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Ingest meters. Every fleet-wide tag combination is registered up front so the series exist (at
 * zero) from the first scrape, which keeps {@code rate()} and absence alerts meaningful.
 *
 * <ul>
 *   <li>{@code driftwatch_scans_ingested_total{outcome}}
 *   <li>{@code driftwatch_scan_ingest_duration_seconds} (histogram)
 *   <li>{@code driftwatch_drift_events_total{direction}}
 *   <li>{@code driftwatch_host_drift_events_total{host_id,hostname,direction}}, the same events per
 *       host (see {@link HostDriftCounters}), labelled with the hostname the agent reported
 * </ul>
 */
@Component
class IngestMetrics {

    enum Outcome {
        CREATED,
        DUPLICATE,
        REJECTED
    }

    private final Map<Outcome, Counter> submissions = new EnumMap<>(Outcome.class);
    private final Map<DriftDirection, Counter> driftEvents = new EnumMap<>(DriftDirection.class);
    private final Timer ingestDuration;
    private final HostDriftCounters hostDriftCounters;

    IngestMetrics(MeterRegistry registry, HostDriftCounters hostDriftCounters) {
        this.hostDriftCounters = hostDriftCounters;
        for (Outcome outcome : Outcome.values()) {
            submissions.put(
                    outcome,
                    Counter.builder("driftwatch.scans.ingested")
                            .description("Scan submissions by outcome")
                            .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                            .register(registry));
        }
        for (DriftDirection direction : DriftDirection.values()) {
            driftEvents.put(
                    direction,
                    Counter.builder("driftwatch.drift.events")
                            .description("Drift events recorded for ingested scans")
                            .tag("direction", direction.name())
                            .register(registry));
        }
        ingestDuration = Timer.builder("driftwatch.scan.ingest.duration")
                .description("Time in the ingest transaction, including commit, per authenticated submission"
                        + " (duplicates and conflicts included)")
                .publishPercentileHistogram()
                .register(registry);
    }

    <T> T timeIngest(Supplier<T> ingest) {
        return ingestDuration.record(ingest);
    }

    void recordOutcome(Outcome outcome) {
        submissions.get(outcome).increment();
    }

    /**
     * Counts the events recorded for a newly ingested scan, fleet-wide and per host. When a late scan
     * causes its successor's events to be re-derived, those replacements are not counted again.
     */
    void recordDriftEvents(UUID hostId, String hostname, Collection<DriftChange> events) {
        events.forEach(event -> driftEvents.get(event.direction()).increment());
        hostDriftCounters.record(hostId, hostname, events);
    }
}
