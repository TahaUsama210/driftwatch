package io.github.tahausama210.driftwatch.hosts;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Facts an agent reports about its host on enrollment and with every scan. */
public record HostFacts(
        @NotNull @Size(min = 1, max = 255) String hostname,
        @NotNull @Valid OsInfo os,
        @NotNull @Size(max = 128) String kernel,
        @NotNull @Size(max = 32) String arch) {}
