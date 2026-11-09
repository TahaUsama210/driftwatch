package io.github.tahausama210.driftwatch.security;

import java.util.UUID;

/**
 * The host an agent token belongs to.
 *
 * @param hostId the authenticated host
 * @param tokenHash hash of the presented token; ingest re-checks it under the host row lock so a token
 *     rotated by a concurrent re-enrollment cannot submit a scan
 */
public record AgentPrincipal(UUID hostId, String tokenHash) {}
