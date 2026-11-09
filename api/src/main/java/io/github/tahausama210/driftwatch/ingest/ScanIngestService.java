package io.github.tahausama210.driftwatch.ingest;

import io.github.tahausama210.driftwatch.checks.CheckCatalogRepository;
import io.github.tahausama210.driftwatch.checks.StatusCounts;
import io.github.tahausama210.driftwatch.common.error.ConflictException;
import io.github.tahausama210.driftwatch.drift.DriftChange;
import io.github.tahausama210.driftwatch.drift.DriftService;
import io.github.tahausama210.driftwatch.ingest.IngestRepository.NewScan;
import io.github.tahausama210.driftwatch.ingest.IngestRepository.ScanRef;
import io.github.tahausama210.driftwatch.scans.ScanQueries;
import io.github.tahausama210.driftwatch.security.AgentPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores, scores and diffs a scan submission in a single transaction.
 *
 * <p><b>Concurrency.</b> The host row is locked first ({@code SELECT … FOR UPDATE}), so submissions
 * for one host are serialised: the previous and next scans read below cannot change before commit,
 * which keeps drift events consistent. Different hosts never contend on each other's rows.
 *
 * <p><b>Out-of-order scans.</b> A scan can arrive after a newer one, e.g. when an agent's retry
 * lands after its next interval's scan. It is stored and scored like any other, its events are
 * computed against its predecessor by {@code finishedAt}, and the events of its successor are
 * re-derived against it, so the event log always equals the diffs of consecutive scans in
 * {@code finishedAt} order. A late scan does not move the latest-scan pointer or overwrite host facts
 * (they would be older than what is stored), but it does update {@code lastSeenAt}.
 */
@Service
class ScanIngestService {

    private static final Logger log = LoggerFactory.getLogger(ScanIngestService.class);

    private final IngestRepository repository;
    private final CheckCatalogRepository catalog;
    private final DriftService drift;
    private final ScanQueries scans;
    private final Clock clock;

    ScanIngestService(
            IngestRepository repository,
            CheckCatalogRepository catalog,
            DriftService drift,
            ScanQueries scans,
            Clock clock) {
        this.repository = repository;
        this.catalog = catalog;
        this.drift = drift;
        this.scans = scans;
        this.clock = clock;
    }

    @Transactional
    public IngestResult ingest(AgentPrincipal agent, ScanReport report) {
        UUID hostId = agent.hostId();
        // Re-checking the token under the lock closes the window in which a concurrent
        // re-enrollment rotates it after this request was authenticated.
        if (!repository.lockHost(hostId, agent.tokenHash())) {
            throw new BadCredentialsException("Agent token was rotated");
        }

        Optional<UUID> owner = repository.findScanOwner(report.scanId());
        if (owner.isPresent()) {
            return duplicate(hostId, owner.get(), report.scanId());
        }

        // Postgres stores microseconds; truncating first keeps in-memory values equal to stored ones.
        Instant receivedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        ScanRef scan = new ScanRef(report.scanId(), report.finishedAt().truncatedTo(ChronoUnit.MICROS));

        // Check ids are unique within a report (validated), so each definition appears once.
        catalog.upsert(
                report.results().stream().map(CheckResultReport::definition).toList(), receivedAt);
        insertScan(hostId, report, scan, receivedAt);
        repository.insertResults(scan.id(), report.results());

        Optional<ScanRef> previous = repository.findPrevious(hostId, scan);
        Optional<ScanRef> next = repository.findNext(hostId, scan);
        List<DriftChange> events = previous.map(p -> drift.recordEvents(hostId, p.id(), scan.id(), scan.finishedAt()))
                .orElse(List.of());
        if (next.isPresent()) {
            drift.rederiveEvents(hostId, scan.id(), next.get().id(), next.get().finishedAt());
            repository.touch(hostId, receivedAt);
        } else {
            repository.recordLatestScan(hostId, scan.id(), report.agentVersion(), report.host(), receivedAt);
        }

        log.atInfo()
                .addKeyValue("hostId", hostId)
                .addKeyValue("scanId", scan.id())
                .addKeyValue("baseline", previous.isEmpty())
                .addKeyValue("outOfOrder", next.isPresent())
                .addKeyValue("driftEvents", events.size())
                .log("Scan ingested");
        return new IngestResult(scans.findSummary(scan.id()).orElseThrow(), true, events);
    }

    private IngestResult duplicate(UUID hostId, UUID ownerId, UUID scanId) {
        if (!ownerId.equals(hostId)) {
            throw scanOwnedByAnotherHost(scanId);
        }
        log.atInfo()
                .addKeyValue("hostId", hostId)
                .addKeyValue("scanId", scanId)
                .log("Duplicate scan submission; returning the stored summary");
        return IngestResult.duplicate(scans.findSummary(scanId).orElseThrow());
    }

    private void insertScan(UUID hostId, ScanReport report, ScanRef scan, Instant receivedAt) {
        NewScan row = new NewScan(
                scan.id(),
                hostId,
                report.agentVersion(),
                report.startedAt().truncatedTo(ChronoUnit.MICROS),
                scan.finishedAt(),
                receivedAt,
                ComplianceScore.of(report.results()),
                StatusCounts.tally(
                        report.results().stream().map(CheckResultReport::status).toList()));
        try {
            repository.insertScan(row);
        } catch (DuplicateKeyException e) {
            // Another host inserted this scan id concurrently, after our ownership check; that
            // host holds its own row lock, not ours, so the primary key is the arbiter.
            throw scanOwnedByAnotherHost(scan.id());
        }
    }

    private static ConflictException scanOwnedByAnotherHost(UUID scanId) {
        return new ConflictException("Scan " + scanId + " was already submitted by another host.");
    }
}
