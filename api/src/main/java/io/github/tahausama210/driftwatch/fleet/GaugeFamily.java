package io.github.tahausama210.driftwatch.fleet;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Gauges sharing one name whose label sets come and go, e.g. one series per host.
 *
 * <p>{@link #replaceAll} updates the value of a series that is still present in place, registers new
 * ones and removes the rest. Unlike Micrometer's {@code MultiGauge} with overwrite, which removes and
 * re-registers every series on each refresh, a series that persists is never briefly absent, so a
 * scrape that lands mid-refresh cannot reset an alert's {@code for:} timer.
 *
 * <p>Not thread-safe; the owner serialises calls.
 */
final class GaugeFamily {

    private final MeterRegistry registry;
    private final String name;
    private final String description;
    private final Map<Tags, Series> series = new HashMap<>();

    GaugeFamily(MeterRegistry registry, String name, String description) {
        this.registry = registry;
        this.name = name;
        this.description = description;
    }

    void replaceAll(Map<Tags, Double> values) {
        for (Iterator<Map.Entry<Tags, Series>> it = series.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Tags, Series> entry = it.next();
            if (!values.containsKey(entry.getKey())) {
                registry.remove(entry.getValue().gauge);
                it.remove();
            }
        }
        values.forEach((tags, value) -> {
            Series existing = series.get(tags);
            if (existing != null) {
                existing.value = value;
            } else {
                series.put(tags, new Series(value, tags));
            }
        });
    }

    private final class Series {

        private volatile double value;
        private final Gauge gauge;

        /** The value is set before registration, so the first scrape of a new series sees it. */
        private Series(double value, Tags tags) {
            this.value = value;
            this.gauge = Gauge.builder(name, this, series -> series.value)
                    .description(description)
                    .tags(tags)
                    .strongReference(true)
                    .register(registry);
        }
    }
}
