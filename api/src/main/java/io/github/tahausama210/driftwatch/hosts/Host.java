package io.github.tahausama210.driftwatch.hosts;

import io.github.tahausama210.driftwatch.scans.ScanSummary;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A host with its newest scan; {@code latestScan} is null until the first scan arrives. */
public record Host(
        UUID id,
        String hostname,
        OsInfo os,
        String kernel,
        String arch,
        String agentVersion,
        Instant enrolledAt,
        Instant lastSeenAt,
        HostStatus status,
        @Nullable ScanSummary latestScan) {}
