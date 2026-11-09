package io.github.tahausama210.driftwatch.hosts;

import com.fasterxml.jackson.annotation.JsonValue;
import io.github.tahausama210.driftwatch.common.WireEnum;

/** Sort keys accepted by {@code GET /api/v1/hosts}. */
public enum HostSortField implements WireEnum {
    HOSTNAME("hostname"),
    SCORE("score"),
    LAST_SEEN_AT("lastSeenAt");

    private final String wireValue;

    HostSortField(String wireValue) {
        this.wireValue = wireValue;
    }

    @Override
    @JsonValue
    public String wireValue() {
        return wireValue;
    }
}
