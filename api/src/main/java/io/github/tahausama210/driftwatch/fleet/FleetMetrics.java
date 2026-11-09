package io.github.tahausama210.driftwatch.fleet;

import io.github.tahausama210.driftwatch.hosts.HostStatusPolicy;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fleet gauges, recomputed on a schedule ({@code driftwatch.metrics-refresh-interval}) rather than
 * on every scrape, so scrape frequency and the number of Prometheus replicas never translate into
 * database load. Exposed as {@code driftwatch_hosts{status}} and {@code driftwatch_fleet_average_score}
 * (NaN until a host has a scored scan, the Prometheus convention for "no value").
 *
 * <p>If a refresh fails, Spring's scheduler logs the exception and the gauges keep their previous
 * values until the next successful run.
 */
@Component
class FleetMetrics {

    private final FleetQueries fleet;
    private final HostStatusPolicy statusPolicy;
    private final AtomicLong onlineHosts = new AtomicLong();
    private final AtomicLong staleHosts = new AtomicLong();
    private volatile double averageScore = Double.NaN;

    FleetMetrics(FleetQueries fleet, HostStatusPolicy statusPolicy, MeterRegistry registry) {
        this.fleet = fleet;
        this.statusPolicy = statusPolicy;
        Gauge.builder("driftwatch.hosts", onlineHosts, AtomicLong::doubleValue)
                .description("Enrolled hosts by status")
                .tag("status", "online")
                .register(registry);
        Gauge.builder("driftwatch.hosts", staleHosts, AtomicLong::doubleValue)
                .description("Enrolled hosts by status")
                .tag("status", "stale")
                .register(registry);
        Gauge.builder("driftwatch.fleet.average.score", this, metrics -> metrics.averageScore)
                .description("Mean compliance score of the hosts' latest scans")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${driftwatch.metrics-refresh-interval}")
    void refresh() {
        FleetQueries.Headline headline = fleet.headline(statusPolicy.staleBefore());
        onlineHosts.set(headline.onlineCount());
        staleHosts.set(headline.hostCount() - headline.onlineCount());
        BigDecimal average = headline.averageScore();
        averageScore = average == null ? Double.NaN : average.doubleValue();
    }
}
