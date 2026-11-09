package io.github.tahausama210.driftwatch.enrollment;

import io.github.tahausama210.driftwatch.security.AgentTokens;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Enrolls hosts and issues agent tokens. */
@Service
public class EnrollmentService {

    /** Result of an enrollment; {@code created} distinguishes a new host from a re-enrollment. */
    public record Enrollment(UUID hostId, String agentToken, boolean created) {

        @Override
        public String toString() {
            return "Enrollment[hostId=" + hostId + ", agentToken=<redacted>, created=" + created + "]";
        }
    }

    private static final Logger log = LoggerFactory.getLogger(EnrollmentService.class);

    private final EnrollmentRepository repository;
    private final Clock clock;

    EnrollmentService(EnrollmentRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Upserts the host by machine id and issues a fresh token. For an existing host the stored hash
     * is replaced, so its previous token stops working as soon as this transaction commits.
     */
    @Transactional
    public Enrollment enroll(EnrollRequest request) {
        String token = AgentTokens.generate();
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        EnrollmentRepository.EnrolledHost host = repository.upsert(
                request.machineId(), request.agentVersion(), request.facts(), AgentTokens.hash(token), now);
        log.atInfo()
                .addKeyValue("hostId", host.hostId())
                .addKeyValue("hostname", request.hostname())
                .addKeyValue("newHost", host.created())
                .log(host.created() ? "Host enrolled" : "Host re-enrolled; agent token rotated");
        return new Enrollment(host.hostId(), token, host.created());
    }
}
