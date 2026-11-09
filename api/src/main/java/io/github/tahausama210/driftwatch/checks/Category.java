package io.github.tahausama210.driftwatch.checks;

import com.fasterxml.jackson.annotation.JsonValue;
import io.github.tahausama210.driftwatch.common.WireEnum;

/** The area of the system a check inspects. */
public enum Category implements WireEnum {
    SSH("ssh"),
    ACCOUNTS("accounts"),
    NETWORK("network"),
    KERNEL("kernel"),
    FILESYSTEM("filesystem");

    private final String wireValue;

    Category(String wireValue) {
        this.wireValue = wireValue;
    }

    @Override
    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    public static Category fromWireValue(String value) {
        return WireEnum.fromWireValue(Category.class, value);
    }
}
