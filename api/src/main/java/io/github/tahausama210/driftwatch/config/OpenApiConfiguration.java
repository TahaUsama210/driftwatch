package io.github.tahausama210.driftwatch.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metadata for the OpenAPI document springdoc generates at {@code /v3/api-docs}. The hand-written
 * {@code contracts/openapi.yaml} remains the source of truth; the integration tests validate every
 * response against it.
 *
 * <p>Dashboard endpoints default to {@value #DASHBOARD_AUTH_SCHEME}, declared here as a plain bearer
 * JWT rather than the contract's OpenID Connect discovery URL so that Swagger UI accepts a pasted
 * access token without depending on which public URL Keycloak is served at.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    public static final String ENROLLMENT_TOKEN_SCHEME = "enrollmentToken";
    public static final String AGENT_TOKEN_SCHEME = "agentToken";
    public static final String DASHBOARD_AUTH_SCHEME = "dashboardAuth";

    @Bean
    OpenAPI driftwatchOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Driftwatch API")
                        .version("0.2.0")
                        .summary("Fleet compliance scanning, scoring and drift detection.")
                        .license(new License().name("MIT").identifier("MIT")))
                .addSecurityItem(new SecurityRequirement().addList(DASHBOARD_AUTH_SCHEME))
                .components(new Components()
                        .addSecuritySchemes(
                                DASHBOARD_AUTH_SCHEME,
                                bearer("Access token (JWT) from the driftwatch Keycloak realm; reads need the viewer"
                                                + " role, decommissioning a host needs admin.")
                                        .bearerFormat("JWT"))
                        .addSecuritySchemes(
                                ENROLLMENT_TOKEN_SCHEME,
                                bearer("Shared enrollment secret (DRIFTWATCH_ENROLLMENT_TOKEN)."))
                        .addSecuritySchemes(
                                AGENT_TOKEN_SCHEME, bearer("Per-host token returned by enrollment, prefixed dwa_.")));
    }

    private static SecurityScheme bearer(String description) {
        return new SecurityScheme()
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .description(description);
    }
}
