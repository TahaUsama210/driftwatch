package io.github.tahausama210.driftwatch.config;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Application settings bound from {@code driftwatch.*}; {@code application.yaml} maps each one to
 * its environment variable. Validated at startup, so a missing or short enrollment token, or a
 * missing OIDC issuer, stops the application before it accepts traffic.
 *
 * @param enrollmentToken shared secret agents present to enroll
 * @param staleAfter a host is {@code STALE} once its last contact is older than this
 * @param corsOrigins browser origins allowed to call the API; empty disables CORS
 * @param metricsRefreshInterval how often the fleet and per-host gauges are recomputed from the database
 * @param oidc how dashboard access tokens are validated
 */
@Validated
@DriftwatchProperties.ValidEnrollmentToken
@ConfigurationProperties("driftwatch")
public record DriftwatchProperties(
        String enrollmentToken,
        @NotNull Duration staleAfter,
        @DefaultValue List<String> corsOrigins,
        @NotNull Duration metricsRefreshInterval,
        @Valid @NotNull Oidc oidc) {

    public static final int MIN_ENROLLMENT_TOKEN_LENGTH = 24;

    public DriftwatchProperties {
        corsOrigins = corsOrigins.stream()
                .map(String::strip)
                .filter(origin -> !origin.isEmpty())
                .toList();
    }

    /** Never render the enrollment secret, e.g. when the properties end up in a debug log. */
    @Override
    public String toString() {
        return "DriftwatchProperties[enrollmentToken=<redacted>, staleAfter=%s, corsOrigins=%s, metricsRefreshInterval=%s, oidc=%s]"
                .formatted(staleAfter, corsOrigins, metricsRefreshInterval, oidc);
    }

    /**
     * The OpenID Connect provider whose access tokens the dashboard endpoints accept.
     *
     * @param issuer the exact {@code iss} claim to accept, e.g.
     *     {@code http://localhost:8400/auth/realms/driftwatch}
     * @param jwksUri where the signing keys are fetched; when unset, Keycloak's endpoint under the
     *     issuer. Deployments point it at the provider's internal address: the issuer is the public
     *     URL, which the API may not be able to reach from inside the cluster or compose network.
     * @param audience a value the {@code aud} claim must contain
     */
    public record Oidc(
            @NotBlank(message = "must be set (DRIFTWATCH_OIDC_ISSUER)")
            @Pattern(regexp = HTTP_URL, message = URL_MESSAGE)
            String issuer,

            @NotBlank @Pattern(regexp = HTTP_URL, message = URL_MESSAGE)
            String jwksUri,

            @NotBlank String audience) {

        static final String KEYCLOAK_JWKS_PATH = "/protocol/openid-connect/certs";
        private static final String HTTP_URL = "https?://\\S+";
        private static final String URL_MESSAGE = "must be an absolute http(s) URL";

        public Oidc {
            if ((jwksUri == null || jwksUri.isBlank()) && issuer != null && !issuer.isBlank()) {
                jwksUri = issuer + KEYCLOAK_JWKS_PATH;
            }
        }
    }

    /**
     * Requires an enrollment token of at least {@value #MIN_ENROLLMENT_TOKEN_LENGTH} characters.
     *
     * <p>Declared on the type rather than the field on purpose: Boot's startup failure report prints
     * the rejected value of a failed <em>field</em> constraint, which here would be the secret.
     */
    @Documented
    @Target(ElementType.TYPE)
    @Retention(RetentionPolicy.RUNTIME)
    @Constraint(validatedBy = EnrollmentTokenValidator.class)
    public @interface ValidEnrollmentToken {

        String message() default
                "driftwatch.enrollment-token (DRIFTWATCH_ENROLLMENT_TOKEN) must be set and at least "
                        + MIN_ENROLLMENT_TOKEN_LENGTH + " characters long";

        Class<?>[] groups() default {};

        Class<? extends Payload>[] payload() default {};
    }

    public static final class EnrollmentTokenValidator
            implements ConstraintValidator<ValidEnrollmentToken, DriftwatchProperties> {

        @Override
        public boolean isValid(DriftwatchProperties properties, ConstraintValidatorContext context) {
            String token = properties.enrollmentToken();
            return token != null && !token.isBlank() && token.length() >= MIN_ENROLLMENT_TOKEN_LENGTH;
        }
    }
}
