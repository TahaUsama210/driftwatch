package io.github.tahausama210.driftwatch.config;

import io.github.tahausama210.driftwatch.common.web.FilterOrder;
import io.github.tahausama210.driftwatch.common.web.RequestBodyLimitFilter;
import io.github.tahausama210.driftwatch.common.web.RequestIdFilter;
import io.github.tahausama210.driftwatch.common.web.WireEnumConverterFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Cross-cutting servlet filters (ordered by {@link FilterOrder}) and MVC conversion. */
@Configuration(proxyBeanMethods = false)
class WebConfiguration implements WebMvcConfigurer {

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverterFactory(new WireEnumConverterFactory());
    }

    @Bean
    FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> registration = new FilterRegistrationBean<>(new RequestIdFilter());
        registration.setOrder(FilterOrder.REQUEST_ID);
        return registration;
    }

    @Bean
    FilterRegistrationBean<RequestBodyLimitFilter> requestBodyLimitFilter(
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        FilterRegistrationBean<RequestBodyLimitFilter> registration = new FilterRegistrationBean<>(
                new RequestBodyLimitFilter(RequestBodyLimitFilter.MAX_BODY_BYTES, exceptionResolver));
        registration.setOrder(FilterOrder.REQUEST_BODY_LIMIT);
        return registration;
    }
}
