package io.github.tahausama210.driftwatch.drift;

import io.github.tahausama210.driftwatch.scans.ScanSummary;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The changes between two scans of a host, in {@link DriftChange#REPORT_ORDER}. */
public record DriftReport(
        UUID hostId,
        @Nullable ScanSummary fromScan,
        @Nullable ScanSummary toScan,
        List<DriftChange> changes) {}
