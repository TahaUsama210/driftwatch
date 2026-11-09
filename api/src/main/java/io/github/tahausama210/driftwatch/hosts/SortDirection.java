package io.github.tahausama210.driftwatch.hosts;

import com.fasterxml.jackson.annotation.JsonValue;
import io.github.tahausama210.driftwatch.common.WireEnum;

/** Sort direction query parameter. */
public enum SortDirection implements WireEnum {
    ASC("asc"),
    DESC("desc");

    private final String wireValue;

    SortDirection(String wireValue) {
        this.wireValue = wireValue;
    }

    @Override
    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    String sql() {
        return name();
    }
}
