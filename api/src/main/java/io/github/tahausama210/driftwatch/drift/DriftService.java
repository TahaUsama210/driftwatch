package io.github.tahausama210.driftwatch.drift;

import io.github.tahausama210.driftwatch.common.error.NotFoundException;
import io.github.tahausama210.driftwatch.hosts.HostQueries;
import io.github.tahausama210.driftwatch.scans.ScanQueries;
import io.github.tahausama210.driftwatch.scans.ScanSummary;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drift detection. Events are a materialised view of status diffs between consecutive scans of a
 * host (by {@code (finished_at, id)}); the invariant is {@code events(S) = diff(previous(S), S)}.
 */
@Service
public class DriftService {

    private final DriftRepository drift;
    private final ScanQueries scans;
    private final HostQueries hosts;

    public DriftService(DriftRepository drift, ScanQueries scans, HostQueries hosts) {
        this.drift = drift;
        this.scans = scans;
        this.hosts = hosts;
    }

    /**
     * Records the events of {@code scanId} relative to its previous scan. Must run in the ingest
     * transaction that holds the host's row lock, so the previous scan cannot change underneath it.
     *
     * @return the recorded changes
     */
    public List<DriftChange> recordEvents(UUID hostId, UUID previousScanId, UUID scanId, Instant detectedAt) {
        List<DriftChange> changes = drift.diff(previousScanId, scanId);
        drift.insertEvents(hostId, previousScanId, scanId, detectedAt, changes);
        return changes;
    }

    /**
     * Re-derives the events of {@code scanId} after an out-of-order scan was inserted directly before
     * it, which makes {@code previousScanId} its new predecessor. Same locking requirement as
     * {@link #recordEvents}.
     */
    public void rederiveEvents(UUID hostId, UUID previousScanId, UUID scanId, Instant detectedAt) {
        drift.deleteEventsForScan(scanId);
        recordEvents(hostId, previousScanId, scanId, detectedAt);
    }

    /**
     * Compares two scans of a host. {@code to} defaults to the latest scan and {@code from} to the
     * scan immediately before {@code to}; explicit scans must belong to the host.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DriftReport report(UUID hostId, @Nullable UUID fromScanId, @Nullable UUID toScanId) {
        if (!hosts.exists(hostId)) {
            throw new NotFoundException("Host " + hostId + " does not exist.");
        }
        Optional<ScanSummary> to =
                toScanId == null ? scans.findLatestForHost(hostId) : Optional.of(requireScan(hostId, toScanId));
        Optional<ScanSummary> from =
                fromScanId == null ? to.flatMap(scans::findPrevious) : Optional.of(requireScan(hostId, fromScanId));
        if (from.isEmpty() || to.isEmpty()) {
            return new DriftReport(hostId, from.orElse(null), to.orElse(null), List.of());
        }
        List<DriftChange> changes = drift.diff(from.get().id(), to.get().id()).stream()
                .sorted(DriftChange.REPORT_ORDER)
                .toList();
        return new DriftReport(hostId, from.get(), to.get(), changes);
    }

    public List<DriftEvent> listEvents(@Nullable UUID hostId, @Nullable DriftDirection direction, int limit) {
        return drift.listEvents(hostId, direction, limit);
    }

    private ScanSummary requireScan(UUID hostId, UUID scanId) {
        return scans.findForHost(hostId, scanId)
                .orElseThrow(() -> new NotFoundException("Host " + hostId + " has no scan " + scanId + "."));
    }
}
