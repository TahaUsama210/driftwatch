package io.github.tahausama210.driftwatch.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class DriftwatchPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(DriftwatchProperties.class)
    static class PropertiesOnly {}

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesOnly.class)
            .withPropertyValues(
                    "driftwatch.stale-after=PT5M",
                    "driftwatch.metrics-refresh-interval=PT15S",
                    "driftwatch.oidc.issuer=" + ISSUER,
                    "driftwatch.oidc.audience=driftwatch-api");

    private static final String ISSUER = "http://localhost:8400/auth/realms/driftwatch";
    private static final String VALID_TOKEN = "exactly-twenty-four-char";

    @Test
    void startupFailsWithoutAnEnrollmentToken() {
        runner.run(context -> assertThat(context)
                .hasFailed()
                .getFailure()
                .rootCause()
                .hasMessageContaining("driftwatch.enrollment-token"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "                              ", "only-twenty-three-chars"})
    void startupFailsWithABlankOrShortTokenWithoutPrintingIt(String token) {
        runner.withPropertyValues("driftwatch.enrollment-token=" + token).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("at least 24 characters");
            if (!token.isBlank()) {
                assertThat(context.getStartupFailure()).rootCause().hasMessageNotContaining(token);
            }
        });
    }

    @Test
    void validSettingsBindAndOriginsAreTrimmed() {
        runner.withPropertyValues(
                        "driftwatch.enrollment-token=exactly-twenty-four-char",
                        "driftwatch.cors-origins= http://localhost:5180 , ,https://dash.example ")
                .run(context -> {
                    DriftwatchProperties properties = context.getBean(DriftwatchProperties.class);
                    assertThat(properties.staleAfter()).isEqualTo(Duration.ofMinutes(5));
                    assertThat(properties.corsOrigins())
                            .containsExactly("http://localhost:5180", "https://dash.example");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "localhost:8400/auth/realms/driftwatch"})
    void startupFailsWithoutAnAbsoluteIssuer(String issuer) {
        runner.withPropertyValues("driftwatch.enrollment-token=" + VALID_TOKEN, "driftwatch.oidc.issuer=" + issuer)
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("driftwatch.oidc.issuer"));
    }

    @Test
    void theMissingIssuerErrorNamesTheEnvironmentVariable() {
        runner.withPropertyValues("driftwatch.enrollment-token=" + VALID_TOKEN, "driftwatch.oidc.issuer=")
                .run(context -> assertThat(context.getStartupFailure())
                        .rootCause()
                        .hasMessageContaining("DRIFTWATCH_OIDC_ISSUER"));
    }

    @Test
    void theJwksUriDefaultsToKeycloaksEndpointUnderTheIssuer() {
        runner.withPropertyValues("driftwatch.enrollment-token=" + VALID_TOKEN)
                .run(context -> assertThat(context.getBean(DriftwatchProperties.class)
                                .oidc()
                                .jwksUri())
                        .isEqualTo(ISSUER + "/protocol/openid-connect/certs"));
    }

    @Test
    void anExplicitJwksUriWins() {
        runner.withPropertyValues(
                        "driftwatch.enrollment-token=" + VALID_TOKEN,
                        "driftwatch.oidc.jwks-uri=http://keycloak:8080/auth/realms/driftwatch/protocol/openid-connect/certs")
                .run(context -> assertThat(context.getBean(DriftwatchProperties.class)
                                .oidc()
                                .jwksUri())
                        .isEqualTo("http://keycloak:8080/auth/realms/driftwatch/protocol/openid-connect/certs"));
    }

    @Test
    void toStringRedactsTheEnrollmentToken() {
        DriftwatchProperties properties = new DriftwatchProperties(
                "super-secret-enrollment-token",
                Duration.ofMinutes(5),
                List.of(),
                Duration.ofSeconds(15),
                new DriftwatchProperties.Oidc(ISSUER, "", "driftwatch-api"));

        assertThat(properties.toString()).doesNotContain("super-secret").contains("<redacted>");
    }
}
