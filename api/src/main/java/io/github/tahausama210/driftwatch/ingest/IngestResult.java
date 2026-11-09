package io.github.tahausama210.driftwatch.ingest;

import io.github.tahausama210.driftwatch.drift.DriftChange;
import io.github.tahausama210.driftwatch.scans.ScanSummary;
import java.util.List;

/**
 * Outcome of a scan submission.
 *
 * @param created false when the scan id had already been submitted by the same host
 * @param driftEvents events recorded for the submitted scan; empty for duplicates and baselines
 */
record IngestResult(ScanSummary summary, boolean created, List<DriftChange> driftEvents) {

    static IngestResult duplicate(ScanSummary summary) {
        return new IngestResult(summary, false, List.of());
    }
}
