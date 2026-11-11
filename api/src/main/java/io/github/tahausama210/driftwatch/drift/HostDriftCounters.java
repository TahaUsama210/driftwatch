package io.github.tahausama210.driftwatch.drift;

import io.github.tahausama210.driftwatch.hosts.HostDecommissioned;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * {@code driftwatch_host_drift_events_total{host_id,hostname,direction}}: drift events per host, so
 * an alert can name the host. In memory, so the series restart from zero with the API; alerts use
 * {@code increase()}.
 *
 * <p>{@code increase()} cannot see the first increment of a series that was never scraped at zero,
 * so all three directions of a host are registered at zero before any increment: when the host
 * enrolls, and on every gauge refresh (which also covers hosts enrolled before a restart). The one
 * gap left is a host whose hostname changes on a scan that also drifts: that scan's events open the
 * series under the new hostname.
 *
 * <p>Series go away with their host: immediately on decommission, and on every refresh for any host
 * id or hostname no longer in the database. The refresh also cleans up after the one race there is:
 * a host's last ingest records its events after its own commit, so it can recreate a series just
 * after the decommission removed it.
 */
@Component
public class HostDriftCounters {

    static final String NAME = "driftwatch.host.drift.events";
    private static final String DESCRIPTION = "Drift events recorded for ingested scans, per host";

    private final MeterRegistry registry;

    HostDriftCounters(MeterRegistry registry) {
        this.registry = registry;
    }

    /** Registers the host's series at zero; leaves existing ones untouched. */
    public void register(UUID hostId, String hostname) {
        for (DriftDirection direction : DriftDirection.values()) {
            counter(hostId, hostname, direction);
        }
    }

    public void record(UUID hostId, String hostname, Collection<DriftChange> events) {
        if (!events.isEmpty()) {
            register(hostId, hostname);
            events.forEach(event -> counter(hostId, hostname, event.direction()).increment());
        }
    }

    /**
     * Makes the registered series match the enrolled hosts exactly.
     *
     * @param hostnames every enrolled host's id and current hostname
     */
    public synchronized void synchronize(Map<UUID, String> hostnames) {
        for (Meter meter : registry.find(NAME).meters()) {
            String hostId = meter.getId().getTag("host_id");
            String hostname = meter.getId().getTag("hostname");
            // Compared as-is, not through String.valueOf: a deleted host whose hostname is literally "null"
            // would otherwise match the absent entry and never be removed.
            if (hostId == null || hostname == null || !hostname.equals(hostnames.get(UUID.fromString(hostId)))) {
                registry.remove(meter);
            }
        }
        hostnames.forEach(this::register);
    }

    @EventListener
    synchronized void forget(HostDecommissioned event) {
        registry.find(NAME).tag("host_id", event.hostId().toString()).meters().forEach(registry::remove);
    }

    private Counter counter(UUID hostId, String hostname, DriftDirection direction) {
        return Counter.builder(NAME)
                .description(DESCRIPTION)
                .tag("host_id", hostId.toString())
                .tag("hostname", hostname)
                .tag("direction", direction.name())
                .register(registry);
    }
}
