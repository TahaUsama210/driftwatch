package io.github.tahausama210.driftwatch.hosts;

import io.github.tahausama210.driftwatch.security.DashboardUser;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Decommissions a host: deletes it with its scans, results and drift events in one transaction.
 *
 * <p>The host row carries the agent token's hash, so the token stops authenticating the moment the
 * transaction commits. An agent that is still running gets {@code 401} on its next submission,
 * re-enrolls and comes back as a new host, as the contract warns.
 *
 * <p><b>Concurrency with ingest.</b> Both lock the host row first. An ingest already holding it
 * finishes (its scan is stored) and the decommission then deletes that scan too. An ingest that waits
 * behind a decommission finds the row gone when the lock is released and is rejected with
 * {@code 401}, writing nothing. Either way no orphaned scan or event survives.
 *
 * <p>The audit line, the counter and the {@link HostDecommissioned} event are produced only after
 * commit, so none of them claims a deletion that was rolled back.
 */
@Service
class DecommissionService {

    /** A dedicated logger, so audit events can be routed or retained separately. */
    private static final Logger audit = LoggerFactory.getLogger("io.github.tahausama210.driftwatch.audit");

    private final DecommissionRepository repository;
    private final TransactionTemplate transactions;
    private final ApplicationEventPublisher events;
    private final Counter decommissioned;

    DecommissionService(
            DecommissionRepository repository,
            TransactionTemplate transactions,
            ApplicationEventPublisher events,
            MeterRegistry registry) {
        this.repository = repository;
        this.transactions = transactions;
        this.events = events;
        this.decommissioned = Counter.builder("driftwatch.hosts.decommissioned")
                .description("Hosts deleted through DELETE /api/v1/hosts/{hostId}")
                .register(registry);
    }

    /** @throws io.github.tahausama210.driftwatch.common.error.NotFoundException if the host does not exist */
    void decommission(UUID hostId, DashboardUser actor) {
        DecommissionRepository.DeletedHost deleted = transactions
                .execute(status -> repository.delete(hostId))
                .orElseThrow(() -> HostController.hostNotFound(hostId));
        decommissioned.increment();
        events.publishEvent(new HostDecommissioned(hostId));
        audit.atInfo()
                .addKeyValue("event.action", "host-decommissioned")
                .addKeyValue("hostId", deleted.hostId())
                .addKeyValue("hostname", deleted.hostname())
                .addKeyValue("deletedScans", deleted.scans())
                .addKeyValue("deletedResults", deleted.results())
                .addKeyValue("deletedDriftEvents", deleted.driftEvents())
                .addKeyValue("actor.sub", actor.subject())
                .addKeyValue("actor.preferredUsername", actor.username())
                .log("Host decommissioned");
    }
}
