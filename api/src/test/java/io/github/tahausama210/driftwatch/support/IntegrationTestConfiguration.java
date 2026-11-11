package io.github.tahausama210.driftwatch.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Beans that {@link ApplicationTest} adds to the application context. */
@TestConfiguration(proxyBeanMethods = false)
class IntegrationTestConfiguration {

    /** Replaces the system clock, so tests control "now" (staleness, skew checks, trend buckets). */
    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock(ApplicationTest.NOW);
    }

    /** Validates every MockMvc response of every integration test against the OpenAPI contract. */
    @Bean
    MockMvcBuilderCustomizer contractValidation() {
        return builder -> builder.alwaysExpect(OpenApiContract.conformsToContract());
    }
}
