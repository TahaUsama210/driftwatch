package io.github.tahausama210.driftwatch.checks;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A check's result on one host's latest scan. */
public record CheckHostStatus(
        UUID hostId,
        String hostname,
        CheckStatus status,
        @Nullable String expected,
        @Nullable String actual,
        @Nullable String evidence,
        UUID scanId,
        Instant finishedAt) {}
