package io.github.tahausama210.driftwatch.ingest;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Counts scan submissions by outcome from the final response status: {@code 201} created,
 * {@code 200} duplicate, any {@code 4xx} rejected. A filter, because rejections happen in several
 * layers (body limit {@code 413}, authentication {@code 401}, validation {@code 400}, ownership
 * {@code 409}) and only the outermost one sees them all. Server errors are left to the standard
 * {@code http_server_requests} metrics.
 */
final class ScanIngestOutcomeFilter extends OncePerRequestFilter {

    static final String PATH = "/api/v1/scans";

    private final IngestMetrics metrics;

    ScanIngestOutcomeFilter(IngestMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(request, response);
        int status = response.getStatus();
        if (status == HttpStatus.CREATED.value()) {
            metrics.recordOutcome(IngestMetrics.Outcome.CREATED);
        } else if (status == HttpStatus.OK.value()) {
            metrics.recordOutcome(IngestMetrics.Outcome.DUPLICATE);
        } else if (HttpStatus.valueOf(status).is4xxClientError()) {
            metrics.recordOutcome(IngestMetrics.Outcome.REJECTED);
        }
    }
}
