package io.github.tahausama210.driftwatch.drift;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class DriftController {

    private final DriftService drift;

    DriftController(DriftService drift) {
        this.drift = drift;
    }

    @GetMapping("/api/v1/hosts/{hostId}/drift")
    @Tag(name = "hosts")
    @Operation(operationId = "getHostDrift", summary = "Compare two scans of a host.")
    DriftReport getHostDrift(
            @PathVariable UUID hostId,
            @RequestParam(required = false) @Nullable UUID from,
            @RequestParam(required = false) @Nullable UUID to) {
        return drift.report(hostId, from, to);
    }

    @GetMapping("/api/v1/events")
    @Tag(name = "events")
    @Operation(operationId = "listDriftEvents", summary = "Recent drift events across the fleet, newest first.")
    List<DriftEvent> listDriftEvents(
            @RequestParam(required = false) @Nullable UUID hostId,
            @RequestParam(required = false) @Nullable DriftDirection direction,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return drift.listEvents(hostId, direction, limit);
    }
}
