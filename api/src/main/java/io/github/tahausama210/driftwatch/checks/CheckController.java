package io.github.tahausama210.driftwatch.checks;

import io.github.tahausama210.driftwatch.common.error.NotFoundException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/checks")
@Tag(name = "checks")
class CheckController {

    private final CheckQueries checks;

    CheckController(CheckQueries checks) {
        this.checks = checks;
    }

    @GetMapping
    @Operation(operationId = "listChecks", summary = "Check catalog with status counts across each host's latest scan.")
    List<CheckSummary> listChecks() {
        return checks.listWithCounts();
    }

    @GetMapping("/{checkId}")
    @Operation(operationId = "getCheck", summary = "One check and its status on every host's latest scan.")
    CheckDetail getCheck(@PathVariable String checkId) {
        return checks.find(checkId).orElseThrow(() -> new NotFoundException("Check " + checkId + " does not exist."));
    }
}
