package io.github.tahausama210.driftwatch.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.tahausama210.driftwatch.support.ApplicationTest;
import io.github.tahausama210.driftwatch.support.TestIdentityProvider;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The API in its own context, started while its JWKS endpoint is unreachable (the identity
 * provider was started to reserve a port, then stopped): it must start, report ready, and begin
 * accepting tokens once the provider comes up, without a restart.
 */
class IdentityProviderOutageIT extends ApplicationTest {

    private static final TestIdentityProvider STOPPED_PROVIDER = TestIdentityProvider.start();
    private static final String JWKS_URI = STOPPED_PROVIDER.jwksUri();

    static {
        STOPPED_PROVIDER.close();
    }

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void unreachableIdentityProvider(DynamicPropertyRegistry registry) {
        registry.add("driftwatch.oidc.issuer", () -> TestIdentityProvider.ISSUER);
        registry.add("driftwatch.oidc.jwks-uri", () -> JWKS_URI);
    }

    @Override
    protected String viewerToken() {
        return STOPPED_PROVIDER.token(clock.instant(), "viewer").sign();
    }

    @Override
    protected String adminToken() {
        return STOPPED_PROVIDER.token(clock.instant(), "admin", "viewer").sign();
    }

    @Test
    void theApiIsReadyWhileTheKeysAreUnreachableAndRecoversWhenTheyReturn() throws Exception {
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        String token = viewerToken();

        // Over real HTTP: the 503 is a generic server-side condition, so it's deliberately outside
        // the per-operation responses in the contract, which MockMvc here enforces.
        HttpResponse<String> duringOutage = getHosts(token);
        assertThat(duringOutage.statusCode())
                .as("a possibly valid token is not reported as invalid")
                .isEqualTo(503);
        assertThat(duringOutage.headers().firstValue(HttpHeaders.CONTENT_TYPE))
                .hasValue(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

        try (TestIdentityProvider recovered = STOPPED_PROVIDER.restart(STOPPED_PROVIDER.port())) {
            assertThat(getHosts(token).statusCode()).isEqualTo(200);
            assertThat(recovered.jwksRequests()).isEqualTo(1);
        }
    }

    private HttpResponse<String> getHosts(String token) throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/hosts"))
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }
}
