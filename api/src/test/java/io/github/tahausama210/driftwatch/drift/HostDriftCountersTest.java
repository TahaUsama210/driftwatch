package io.github.tahausama210.driftwatch.drift;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HostDriftCountersTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final HostDriftCounters counters = new HostDriftCounters(registry);

    /**
     * A deleted host's series can outlive its decommission (an in-flight ingest records its events
     * after the decommission removed them); the refresh must drop them whatever the hostname was.
     */
    @ParameterizedTest
    @ValueSource(strings = {"ghost-01", "null"})
    void theSeriesOfAHostThatNoLongerExistsAreRemoved(String hostname) {
        UUID kept = UUID.randomUUID();
        counters.register(UUID.randomUUID(), hostname);

        counters.synchronize(Map.of(kept, "web-01"));

        assertThat(registry.find(HostDriftCounters.NAME).meters())
                .hasSize(DriftDirection.values().length)
                .allSatisfy(meter -> assertThat(hostId(meter)).isEqualTo(kept.toString()));
    }

    private static String hostId(Meter meter) {
        return meter.getId().getTag("host_id");
    }
}
