package io.github.tahausama210.driftwatch.scans;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.List;

/** A scan with all of its results, sorted by category and then check id. */
public record ScanDetail(@JsonUnwrapped ScanSummary summary, String hostname, List<CheckResultView> results) {}
