package io.github.tahausama210.driftwatch.enrollment;

import io.github.tahausama210.driftwatch.hosts.HostFacts;
import io.github.tahausama210.driftwatch.hosts.OsInfo;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Host facts plus the agent's identity; the JSON is flat, as in the contract's {@code allOf}. */
public record EnrollRequest(
        @NotNull @Size(min = 8, max = 64) String machineId,
        @NotNull @Size(max = 32) String agentVersion,
        @NotNull @Size(min = 1, max = 255) String hostname,
        @NotNull @Valid OsInfo os,
        @NotNull @Size(max = 128) String kernel,
        @NotNull @Size(max = 32) String arch) {

    HostFacts facts() {
        return new HostFacts(hostname, os, kernel, arch);
    }
}
