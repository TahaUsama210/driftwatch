package io.github.tahausama210.driftwatch.enrollment;

import java.util.UUID;

/** The host id and its new agent token. The token is shown once and never stored. */
public record EnrollResponse(UUID hostId, String agentToken) {

    @Override
    public String toString() {
        return "EnrollResponse[hostId=" + hostId + ", agentToken=<redacted>]";
    }
}
