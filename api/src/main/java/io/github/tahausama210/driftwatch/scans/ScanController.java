package io.github.tahausama210.driftwatch.scans;

import io.github.tahausama210.driftwatch.common.error.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "scans")
class ScanController {

    private final ScanQueries scans;

    ScanController(ScanQueries scans) {
        this.scans = scans;
    }

    @GetMapping("/api/v1/scans/{scanId}")
    @Operation(operationId = "getScan", summary = "A scan with all of its check results.")
    ScanDetail getScan(@PathVariable UUID scanId) {
        return scans.findDetail(scanId).orElseThrow(() -> new NotFoundException("Scan " + scanId + " does not exist."));
    }
}
