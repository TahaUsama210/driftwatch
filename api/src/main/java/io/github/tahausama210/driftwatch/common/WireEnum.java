package io.github.tahausama210.driftwatch.common;

import java.util.Arrays;
import java.util.List;

/**
 * An enum whose API representation (JSON and query strings) differs from its constant name, e.g.
 * {@code Severity.HIGH} is {@code "high"} on the wire. Implementations annotate {@link #wireValue()}
 * with {@code @JsonValue}; query parameters are converted by {@code WireEnumConverterFactory}.
 */
public interface WireEnum {

    String wireValue();

    static <E extends Enum<E> & WireEnum> E fromWireValue(Class<E> type, String value) {
        for (E constant : type.getEnumConstants()) {
            if (constant.wireValue().equals(value)) {
                return constant;
            }
        }
        throw new IllegalArgumentException("must be one of " + allowedValues(type));
    }

    /** The values a client may send for {@code enumType}, in declaration order. */
    static List<String> allowedValues(Class<?> enumType) {
        return Arrays.stream(enumType.getEnumConstants())
                .map(constant ->
                        constant instanceof WireEnum wireEnum ? wireEnum.wireValue() : ((Enum<?>) constant).name())
                .toList();
    }
}
