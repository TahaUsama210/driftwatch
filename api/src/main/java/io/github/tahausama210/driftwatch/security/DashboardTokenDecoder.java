package io.github.tahausama210.driftwatch.security;

import io.github.tahausama210.driftwatch.config.DriftwatchProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;

/**
 * Builds the decoder for dashboard access tokens issued by the OIDC provider (Keycloak).
 *
 * <p><b>Keys.</b> The signing keys come from the JWKS endpoint, fetched on the first token that needs
 * them, not at startup, so the API starts and reports ready while Keycloak is down. Nimbus caches the
 * key set (five minutes) and fetches it again early when a token names a key id it does not hold,
 * which is how a Keycloak key rotation is picked up. While the endpoint is unreachable and nothing is
 * cached, token validation fails as a retryable {@code 503}, not as a bad token: the token may be
 * perfectly valid. Fetches are serialised behind one lock, so the timeouts here are short; Spring's
 * defaults (30 s each) would stall every dashboard request behind a blackholed IdP.
 *
 * <p><b>Validation.</b> Only RS256 signatures (Keycloak's default; this also rules out {@code none}
 * and HMAC algorithm-confusion tokens), {@code typ} absent or {@code JWT}, an {@code exp} that is
 * required and not in the past (60 s skew), the exact issuer, an {@code aud} that contains the
 * configured audience, and a {@code sub}. Time comes from the application clock, like every other
 * time check.
 */
final class DashboardTokenDecoder {

    static final Duration CLOCK_SKEW = Duration.ofSeconds(60);
    static final Duration JWKS_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration JWKS_READ_TIMEOUT = Duration.ofSeconds(5);

    private DashboardTokenDecoder() {}

    static JwtDecoder create(DriftwatchProperties.Oidc oidc, Clock clock) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(JWKS_CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(JWKS_READ_TIMEOUT);

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(oidc.jwksUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .restOperations(new RestTemplate(requestFactory))
                .build();

        JwtTimestampValidator timestamps = new JwtTimestampValidator(CLOCK_SKEW);
        timestamps.setClock(clock);
        // Spring accepts a token without exp by default, which would never expire.
        timestamps.setAllowEmptyExpiryClaim(false);
        decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(List.of(
                timestamps,
                new JwtIssuerValidator(oidc.issuer()),
                new JwtAudienceValidator(oidc.audience()),
                // Audit logs identify the actor by sub, so a token without one is not accepted.
                new JwtClaimValidator<String>(JwtClaimNames.SUB, subject -> subject != null && !subject.isBlank()))));
        return decoder;
    }
}
