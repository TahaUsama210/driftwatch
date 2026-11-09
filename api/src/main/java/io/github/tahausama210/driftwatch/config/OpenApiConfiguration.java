package io.github.tahausama210.driftwatch.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Metadata for the OpenAPI document springdoc generates at {@code /v3/api-docs}. The hand-written
 * {@code contracts/openapi.yaml} remains the source of truth; the integration tests validate every
 * response against it.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    public static final String ENROLLMENT_TOKEN_SCHEME = "enrollmentToken";
    public static final String AGENT_TOKEN_SCHEME = "agentToken";

    @Bean
    OpenAPI driftwatchOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Driftwatch API")
                        .version("0.1.0")
                        .summary("Fleet compliance scanning, scoring and drift detection.")
                        .license(new License().name("MIT").identifier("MIT")))
                .components(new Components()
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
