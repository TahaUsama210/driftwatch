package io.github.tahausama210.driftwatch.config;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotNull;
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
 * its environment variable. Validated at startup, so a missing or short enrollment token stops the
 * application before it accepts traffic.
 *
 * @param enrollmentToken shared secret agents present to enroll
 * @param staleAfter a host is {@code STALE} once its last contact is older than this
 * @param corsOrigins browser origins allowed to call the API; empty disables CORS
 * @param metricsRefreshInterval how often the fleet gauges are recomputed from the database
 */
@Validated
@DriftwatchProperties.ValidEnrollmentToken
@ConfigurationProperties("driftwatch")
public record DriftwatchProperties(
        String enrollmentToken,
        @NotNull Duration staleAfter,
        @DefaultValue List<String> corsOrigins,
        @NotNull Duration metricsRefreshInterval) {

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
        return "DriftwatchProperties[enrollmentToken=<redacted>, staleAfter=%s, corsOrigins=%s, metricsRefreshInterval=%s]"
                .formatted(staleAfter, corsOrigins, metricsRefreshInterval);
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
