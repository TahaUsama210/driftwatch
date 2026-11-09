package io.github.tahausama210.driftwatch.ingest;

import io.github.tahausama210.driftwatch.common.web.FilterOrder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the ingest outcome metrics filter for {@code POST /api/v1/scans} only. */
@Configuration(proxyBeanMethods = false)
class IngestWebConfiguration {

    @Bean
    FilterRegistrationBean<ScanIngestOutcomeFilter> scanIngestOutcomeFilter(IngestMetrics metrics) {
        FilterRegistrationBean<ScanIngestOutcomeFilter> registration =
                new FilterRegistrationBean<>(new ScanIngestOutcomeFilter(metrics));
        registration.addUrlPatterns(ScanIngestOutcomeFilter.PATH);
        registration.setOrder(FilterOrder.INGEST_OUTCOME_METRICS);
        return registration;
    }
}
