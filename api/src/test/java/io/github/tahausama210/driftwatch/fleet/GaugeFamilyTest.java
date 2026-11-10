package io.github.tahausama210.driftwatch.fleet;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GaugeFamilyTest {

    private static final Tags A = Tags.of("host_id", "a");
    private static final Tags B = Tags.of("host_id", "b");

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final GaugeFamily family = new GaugeFamily(registry, "test.gauge", "A test gauge");

    @Test
    void newSeriesAreRegisteredWithTheirValues() {
        family.replaceAll(Map.of(A, 1.0, B, 2.0));

        assertThat(value(A)).isEqualTo(1.0);
        assertThat(value(B)).isEqualTo(2.0);
    }

    @Test
    void aSeriesThatStaysIsUpdatedInPlaceNotReRegistered() {
        family.replaceAll(Map.of(A, 1.0));
        Gauge before = registry.get("test.gauge").tags(A).gauge();

        family.replaceAll(Map.of(A, 5.0));

        assertThat(registry.get("test.gauge").tags(A).gauge()).isSameAs(before);
        assertThat(before.value()).isEqualTo(5.0);
    }

    @Test
    void seriesMissingFromTheNextRefreshAreRemoved() {
        family.replaceAll(Map.of(A, 1.0, B, 2.0));

        family.replaceAll(Map.of(B, 3.0));

        assertThat(registry.find("test.gauge").tags(A).gauge()).isNull();
        assertThat(value(B)).isEqualTo(3.0);
        family.replaceAll(Map.of());
        assertThat(registry.find("test.gauge").gauges()).isEmpty();
    }

    private double value(Tags tags) {
        return registry.get("test.gauge").tags(tags).gauge().value();
    }
}
