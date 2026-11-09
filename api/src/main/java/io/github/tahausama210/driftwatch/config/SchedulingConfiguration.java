package io.github.tahausama210.driftwatch.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables {@code @Scheduled} methods (the fleet gauge refresh). */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class SchedulingConfiguration {}
