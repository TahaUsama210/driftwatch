package io.github.tahausama210.driftwatch.enrollment;

import io.github.tahausama210.driftwatch.config.OpenApiConfiguration;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "agents")
class EnrollmentController {

    private final EnrollmentService enrollment;

    EnrollmentController(EnrollmentService enrollment) {
        this.enrollment = enrollment;
    }

    /** {@code 201} for a new host, {@code 200} when an existing host re-enrolls. */
    @PostMapping("/api/v1/agents/enroll")
    @Operation(
            operationId = "enrollAgent",
            summary = "Enroll (or re-enroll) a host and obtain an agent token.",
            security = @SecurityRequirement(name = OpenApiConfiguration.ENROLLMENT_TOKEN_SCHEME))
    ResponseEntity<EnrollResponse> enroll(@Valid @RequestBody EnrollRequest request) {
        EnrollmentService.Enrollment result = enrollment.enroll(request);
        EnrollResponse body = new EnrollResponse(result.hostId(), result.agentToken());
        return result.created()
                ? ResponseEntity.created(URI.create("/api/v1/hosts/" + result.hostId()))
                        .body(body)
                : ResponseEntity.ok(body);
    }
}
