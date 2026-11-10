package io.github.tahausama210.driftwatch.security;

import static io.github.tahausama210.driftwatch.support.ScanReports.ROOT_LOGIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.tahausama210.driftwatch.support.IntegrationTest;
import io.github.tahausama210.driftwatch.support.ScanReports;
import io.github.tahausama210.driftwatch.support.TestIdentityProvider;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * Real token validation: tokens are signed with the test identity provider's RSA key, and the API
 * fetches the public key from its JWKS endpoint over HTTP, as it would from Keycloak.
 */
@ExtendWith(OutputCaptureExtension.class)
class DashboardAuthenticationIT extends IntegrationTest {

    private static final String INVALID_TOKEN_CHALLENGE = "Bearer error=\"invalid_token\"";

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api/v1/fleet/summary",
                "/api/v1/fleet/trend",
                "/api/v1/hosts",
                "/api/v1/checks",
                "/api/v1/events"
            })
    void aViewerTokenReadsEveryCollection(String path) throws Exception {
        mvc.perform(get(path).with(asViewer())).andExpect(status().isOk());
    }

    @Test
    void anAdminTokenWithoutTheViewerRoleStillReadsBecauseAdminImpliesViewer() throws Exception {
        String adminOnly = IDENTITY_PROVIDER.token(clock.instant(), "admin").sign();

        mvc.perform(get("/api/v1/hosts").with(bearer(adminOnly))).andExpect(status().isOk());
    }

    @Test
    void aValidTokenWithoutADriftwatchRoleIsForbidden() throws Exception {
        String noRoles = IDENTITY_PROVIDER
                .token(clock.instant(), "offline_access", "uma_authorization")
                .sign();

        mvc.perform(get("/api/v1/hosts").with(bearer(noRoles)))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.type").value("about:blank"));
    }

    @Test
    void aMissingTokenGetsTheBareBearerChallenge() throws Exception {
        mvc.perform(get("/api/v1/hosts"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("A valid bearer token is required."));
    }

    static Stream<Arguments> invalidTokens() {
        return Stream.of(
                invalid("wrong issuer", token -> token.issuer("http://localhost:38080/auth/realms/driftwatch")),
                invalid("wrong audience", token -> token.audience(List.of("account"))),
                invalid("no audience", token -> token.audience(null)),
                invalid("expired beyond the clock skew", token -> token.expiresAt(NOW.minus(Duration.ofSeconds(61)))),
                invalid("no expiry", token -> token.expiresAt(null)),
                invalid("no subject", token -> token.subject(null)),
                invalid("signed by an unknown key", TestIdentityProvider.Token::signedByAnotherKey),
                invalid("HS256 keyed with the public key", TestIdentityProvider.Token::hmacSignedWithThePublicKey),
                invalid("unsigned (alg none)", TestIdentityProvider.Token::unsigned));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidTokens")
    void anInvalidTokenIsUnauthorizedWithAnInvalidTokenChallenge(
            String description, Function<TestIdentityProvider.Token, TestIdentityProvider.Token> mutation)
            throws Exception {
        String token = mutation.apply(IDENTITY_PROVIDER.token(clock.instant(), "admin", "viewer"))
                .sign();

        mvc.perform(get("/api/v1/hosts").with(bearer(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, INVALID_TOKEN_CHALLENGE))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("A valid bearer token is required."));
    }

    @Test
    void aTokenThatExpiredWithinTheClockSkewIsStillAccepted() throws Exception {
        String token = IDENTITY_PROVIDER
                .token(clock.instant(), "viewer")
                .expiresAt(NOW.minus(Duration.ofSeconds(30)))
                .sign();

        mvc.perform(get("/api/v1/hosts").with(bearer(token))).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-jwt", "a.b.c", "Bearer two words"})
    void aMalformedTokenIsUnauthorized(String token) throws Exception {
        mvc.perform(get("/api/v1/hosts").with(bearer(token)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void agentAndEnrollmentTokensAreNeverAcceptedOnDashboardEndpoints() throws Exception {
        Agent agent = enroll("machine-0001");

        mvc.perform(get("/api/v1/hosts").with(bearer(agent.token())))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, INVALID_TOKEN_CHALLENGE));
        mvc.perform(get("/api/v1/hosts").with(bearer(ENROLLMENT_TOKEN))).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/hosts/{id}", agent.hostId()).with(bearer(agent.token())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aDashboardTokenIsNeverAcceptedOnAgentEndpoints() throws Exception {
        enroll("machine-0001");

        submitScan(adminToken(), ScanReports.scan(NOW).result(ROOT_LOGIN, "high", "PASS"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(post("/api/v1/agents/enroll")
                        .with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(enrollBody("machine-0002", "web-02"))))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.sql("SELECT count(*) FROM scans").query(Long.class).single())
                .isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM hosts").query(Long.class).single())
                .isOne();
    }

    @Test
    void theKeySetIsFetchedOnceAndCachedAcrossRequests() throws Exception {
        mvc.perform(get("/api/v1/hosts").with(asViewer())).andExpect(status().isOk());
        int fetchesAfterFirstRequest = IDENTITY_PROVIDER.jwksRequests();

        for (int i = 0; i < 5; i++) {
            mvc.perform(get("/api/v1/hosts").with(asViewer())).andExpect(status().isOk());
        }

        assertThat(fetchesAfterFirstRequest).isPositive();
        assertThat(IDENTITY_PROVIDER.jwksRequests()).isEqualTo(fetchesAfterFirstRequest);
    }

    @Test
    void aRejectedTokenIsLoggedWithItsReasonButNeverTheToken(CapturedOutput output) throws Exception {
        String token = IDENTITY_PROVIDER
                .token(clock.instant(), "viewer")
                .issuer("https://wrong.example/realms/driftwatch")
                .subject(UUID.randomUUID().toString())
                .sign();

        mvc.perform(get("/api/v1/hosts").with(bearer(token))).andExpect(status().isUnauthorized());

        assertThat(output.getOut())
                .contains("Rejected a dashboard access token")
                .contains("iss claim is not valid")
                .doesNotContain(token);
    }

    private static Arguments invalid(
            String description, Function<TestIdentityProvider.Token, TestIdentityProvider.Token> mutation) {
        return Arguments.of(description, mutation);
    }
}
