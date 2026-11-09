package io.github.tahausama210.driftwatch.drift;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.Instant;
import java.util.UUID;

/**
 * A drift change recorded on ingest.
 *
 * @param scanId the scan in which the change was observed
 * @param detectedAt {@code finishedAt} of that scan
 */
public record DriftEvent(
        UUID id,
        UUID hostId,
        String hostname,
        UUID scanId,
        Instant detectedAt,
        @JsonUnwrapped DriftChange change) {}
