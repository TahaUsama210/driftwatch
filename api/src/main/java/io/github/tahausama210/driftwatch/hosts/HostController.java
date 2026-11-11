package io.github.tahausama210.driftwatch.hosts;

import io.github.tahausama210.driftwatch.common.error.NotFoundException;
import io.github.tahausama210.driftwatch.scans.ScanQueries;
import io.github.tahausama210.driftwatch.scans.ScanSummary;
import io.github.tahausama210.driftwatch.security.DashboardUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hosts")
@Tag(name = "hosts")
class HostController {

    private final HostQueries hosts;
    private final ScanQueries scans;
    private final HostStatusPolicy statusPolicy;
    private final DecommissionService decommissions;

    HostController(
            HostQueries hosts, ScanQueries scans, HostStatusPolicy statusPolicy, DecommissionService decommissions) {
        this.hosts = hosts;
        this.scans = scans;
        this.statusPolicy = statusPolicy;
        this.decommissions = decommissions;
    }

    @GetMapping
    @Operation(operationId = "listHosts", summary = "List hosts with their latest scan.")
    HostPage listHosts(
            @RequestParam(required = false) @Size(max = 255) @Nullable String query,
            @RequestParam(required = false) @Nullable HostStatus status,
            @RequestParam(defaultValue = "hostname") HostSortField sort,
            @RequestParam(defaultValue = "asc") SortDirection direction,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
        return hosts.search(new HostSearch(query, status, sort, direction, page, size), statusPolicy.staleBefore());
    }

    @GetMapping("/{hostId}")
    @Operation(operationId = "getHost", summary = "One host with its latest scan.")
    Host getHost(@PathVariable UUID hostId) {
        return hosts.find(hostId, statusPolicy.staleBefore()).orElseThrow(() -> hostNotFound(hostId));
    }

    /** The admin role is enforced by the security configuration, before this method is reached. */
    @DeleteMapping("/{hostId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(operationId = "decommissionHost", summary = "Decommission a host (admin only).")
    void decommissionHost(@PathVariable UUID hostId, @AuthenticationPrincipal Jwt token) {
        decommissions.decommission(hostId, DashboardUser.of(token));
    }

    @GetMapping("/{hostId}/scans")
    @Operation(operationId = "listHostScans", summary = "Scan history for a host, newest first.")
    List<ScanSummary> listHostScans(
            @PathVariable UUID hostId, @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit) {
        if (!hosts.exists(hostId)) {
            throw hostNotFound(hostId);
        }
        return scans.listForHost(hostId, limit);
    }

    static NotFoundException hostNotFound(UUID hostId) {
        return new NotFoundException("Host " + hostId + " does not exist.");
    }
}
