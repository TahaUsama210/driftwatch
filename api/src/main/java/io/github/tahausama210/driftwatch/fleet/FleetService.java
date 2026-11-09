package io.github.tahausama210.driftwatch.fleet;

import io.github.tahausama210.driftwatch.checks.CheckQueries;
import io.github.tahausama210.driftwatch.hosts.HostStatusPolicy;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class FleetService {

    static final int TOP_FAILING_CHECKS = 5;

    private final FleetQueries fleet;
    private final CheckQueries checks;
    private final HostStatusPolicy statusPolicy;
    private final Clock clock;

    FleetService(FleetQueries fleet, CheckQueries checks, HostStatusPolicy statusPolicy, Clock clock) {
        this.fleet = fleet;
        this.checks = checks;
        this.statusPolicy = statusPolicy;
        this.clock = clock;
    }

    /** Repeatable read gives the three queries one snapshot, so the numbers agree with each other. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FleetSummary summary() {
        FleetQueries.Headline headline = fleet.headline(statusPolicy.staleBefore());
        List<CheckFailure> topFailing = checks.listWithCounts().stream()
                .filter(check -> check.counts().failing() > 0)
                .limit(TOP_FAILING_CHECKS)
                .map(CheckFailure::of)
                .toList();
        return new FleetSummary(
                headline.hostCount(),
                headline.onlineCount(),
                headline.hostCount() - headline.onlineCount(),
                headline.averageScore(),
                fleet.hostsWithCriticalFailures(),
                topFailing,
                headline.lastScanAt());
    }

    public FleetTrend trend(int hours, int bucketMinutes) {
        TrendWindow window = TrendWindow.ending(clock.instant(), hours, bucketMinutes);
        return new FleetTrend(bucketMinutes, fleet.trend(window));
    }
}
