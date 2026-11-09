package io.github.tahausama210.driftwatch.hosts;

import io.github.tahausama210.driftwatch.config.DriftwatchProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Component;

/** Decides the cut-off for {@link HostStatus#STALE} from the clock and {@code DRIFTWATCH_STALE_AFTER}. */
@Component
public class HostStatusPolicy {

    private final Clock clock;
    private final Duration staleAfter;

    public HostStatusPolicy(Clock clock, DriftwatchProperties properties) {
        this.clock = clock;
        this.staleAfter = properties.staleAfter();
    }

    /**
     * Hosts last seen before this instant are stale. Compute once per request and reuse it.
     *
     * <p>Truncated to the microseconds Postgres stores: the JDBC driver rounds a sub-microsecond
     * value when binding it, so the SQL status filters and {@link HostStatus#of} would otherwise
     * disagree about a host seen within a microsecond of the cut-off.
     */
    public Instant staleBefore() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS).minus(staleAfter);
    }
}
