package io.github.tahausama210.driftwatch.common.web;

import io.github.tahausama210.driftwatch.common.WireEnum;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.converter.ConverterFactory;

/** Converts query-string values such as {@code sort=lastSeenAt} into their {@link WireEnum} constant. */
public final class WireEnumConverterFactory implements ConverterFactory<String, WireEnum> {

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends WireEnum> Converter<String, T> getConverter(Class<T> targetType) {
        // Every WireEnum is an enum; the raw call bridges the intersection type the compiler cannot express here.
        return source -> (T) WireEnum.fromWireValue((Class) targetType, source);
    }
}
