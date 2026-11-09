package io.github.tahausama210.driftwatch.fleet;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/fleet")
@Tag(name = "fleet")
class FleetController {

    private final FleetService fleet;

    FleetController(FleetService fleet) {
        this.fleet = fleet;
    }

    @GetMapping("/summary")
    @Operation(operationId = "getFleetSummary", summary = "Headline numbers for the fleet overview.")
    FleetSummary getFleetSummary() {
        return fleet.summary();
    }

    @GetMapping("/trend")
    @Operation(operationId = "getFleetTrend", summary = "Fleet average score over time.")
    FleetTrend getFleetTrend(
            @RequestParam(defaultValue = "24") @Min(1) @Max(720) int hours,
            @RequestParam(defaultValue = "60") @Min(1) @Max(1440) int bucketMinutes) {
        return fleet.trend(hours, bucketMinutes);
    }
}
