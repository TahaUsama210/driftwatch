package io.github.tahausama210.driftwatch.scans;

import io.github.tahausama210.driftwatch.checks.StatusCounts;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A scan without its individual results.
 *
 * @param score compliance score with one decimal place; null when no check was applicable
 */
public record ScanSummary(
        UUID id,
        UUID hostId,
        String agentVersion,
        Instant startedAt,
        Instant finishedAt,
        Instant receivedAt,
        @Nullable BigDecimal score,
        StatusCounts counts) {}
