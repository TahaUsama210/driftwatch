package io.github.tahausama210.driftwatch.hosts;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/**
 * Operating system facts from {@code /etc/os-release}.
 *
 * @param id {@code ID}, e.g. {@code ubuntu}
 * @param version {@code VERSION_ID}, absent on rolling releases
 * @param prettyName {@code PRETTY_NAME}
 */
public record OsInfo(
        @NotNull @Size(max = 64) String id,
        @Size(max = 64) @Nullable String version,
        @NotNull @Size(max = 255) String prettyName) {}
