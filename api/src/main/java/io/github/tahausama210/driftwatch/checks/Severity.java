package io.github.tahausama210.driftwatch.checks;

import com.fasterxml.jackson.annotation.JsonValue;
import io.github.tahausama210.driftwatch.common.WireEnum;

/** How much a check contributes to the compliance score. */
public enum Severity implements WireEnum {
    LOW("low", 1),
    MEDIUM("medium", 2),
    HIGH("high", 3),
    CRITICAL("critical", 5);

    private final String wireValue;
    private final int weight;

    Severity(String wireValue, int weight) {
        this.wireValue = wireValue;
        this.weight = weight;
    }

    @Override
    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    public int weight() {
        return weight;
    }

    public static Severity fromWireValue(String value) {
        return WireEnum.fromWireValue(Severity.class, value);
    }
}
