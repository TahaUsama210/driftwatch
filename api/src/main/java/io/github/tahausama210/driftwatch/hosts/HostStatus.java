package io.github.tahausama210.driftwatch.hosts;

import java.time.Instant;

/** Whether a host has been heard from recently. */
public enum HostStatus {
    ONLINE,
    STALE;

    /** {@code STALE} when the last contact happened strictly before {@code staleBefore}. */
    public static HostStatus of(Instant lastSeenAt, Instant staleBefore) {
        return lastSeenAt.isBefore(staleBefore) ? STALE : ONLINE;
    }
}
