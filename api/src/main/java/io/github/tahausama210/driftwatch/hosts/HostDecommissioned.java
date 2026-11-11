package io.github.tahausama210.driftwatch.hosts;

import java.util.UUID;

/**
 * Published after a decommission has committed, so components that keep per-host state in memory
 * (metric series) can drop it without the hosts package depending on them.
 */
public record HostDecommissioned(UUID hostId) {}
