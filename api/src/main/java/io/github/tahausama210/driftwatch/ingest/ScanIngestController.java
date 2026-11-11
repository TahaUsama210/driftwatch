package io.github.tahausama210.driftwatch.ingest;

import io.github.tahausama210.driftwatch.config.OpenApiConfiguration;
import io.github.tahausama210.driftwatch.scans.ScanSummary;
import io.github.tahausama210.driftwatch.security.AgentPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "agents")
class ScanIngestController {

    private final ScanIngestService ingestService;
    private final ScanReportValidator reportValidator;
    private final IngestMetrics metrics;

    ScanIngestController(ScanIngestService ingestService, ScanReportValidator reportValidator, IngestMetrics metrics) {
        this.ingestService = ingestService;
        this.reportValidator = reportValidator;
        this.metrics = metrics;
    }

    /** Adds the cross-field rules to Bean Validation for the request body. */
    @InitBinder("scanReport")
    void registerValidator(WebDataBinder binder) {
        binder.addValidators(reportValidator);
    }

    /** {@code 201} for a new scan, {@code 200} with the stored summary for a duplicate submission. */
    @PostMapping("/api/v1/scans")
    @Tag(name = "scans")
    @Operation(
            operationId = "submitScan",
            summary = "Submit a scan report for the authenticated host.",
            security = @SecurityRequirement(name = OpenApiConfiguration.AGENT_TOKEN_SCHEME))
    ResponseEntity<ScanSummary> submitScan(
            @Parameter(hidden = true) @AuthenticationPrincipal AgentPrincipal agent,
            @Valid @RequestBody ScanReport report) {
        // Timed here rather than inside the service so the measurement includes the commit.
        IngestResult result = metrics.timeIngest(() -> ingestService.ingest(agent, report));
        metrics.recordDriftEvents(agent.hostId(), report.host().hostname(), result.driftEvents());
        return result.created()
                ? ResponseEntity.created(
                                URI.create("/api/v1/scans/" + result.summary().id()))
                        .body(result.summary())
                : ResponseEntity.ok(result.summary());
    }
}
