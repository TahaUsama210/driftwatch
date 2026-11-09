package io.github.tahausama210.driftwatch.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the system UTC clock as a bean. Everything time-dependent (staleness, clock-skew
 * validation, trend buckets, receive timestamps) reads time from this bean so tests can pin it.
 */
@Configuration(proxyBeanMethods = false)
class ClockConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
