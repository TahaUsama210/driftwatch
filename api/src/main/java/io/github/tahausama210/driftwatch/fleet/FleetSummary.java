package io.github.tahausama210.driftwatch.fleet;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Headline numbers for the fleet overview.
 *
 * @param averageScore mean of the hosts' latest scan scores, one decimal; hosts whose latest scan has
 *     no score (or that have no scan) are left out; null when no host has a scored latest scan
 * @param hostsWithCriticalFailures hosts whose latest scan fails at least one critical check
 * @param topFailingChecks up to five checks with failing hosts, most impactful first
 * @param lastScanAt the newest {@code finishedAt} across the fleet
 */
public record FleetSummary(
        long hostCount,
        long onlineCount,
        long staleCount,
        @Nullable BigDecimal averageScore,
        long hostsWithCriticalFailures,
        List<CheckFailure> topFailingChecks,
        @Nullable Instant lastScanAt) {}
