package io.github.tahausama210.driftwatch.security;

import static io.github.tahausama210.driftwatch.support.ScanReports.ROOT_LOGIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.tahausama210.driftwatch.support.ApplicationTest;
import io.github.tahausama210.driftwatch.support.ScanReports;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/**
 * The API against the real Keycloak image with the realm file that compose and Helm import: tokens
 * from the smoke clients' client-credentials grant must authorise exactly as the matrix in
 * docs/CONTRACTS.md says. This is what proves the realm's audience mapper and role scope mappings
 * produce tokens the API accepts.
 */
@Testcontainers
class KeycloakIT extends ApplicationTest {

    private static final Path REALM = Path.of("..", "deploy", "helm", "driftwatch", "files", "keycloak");

    @Container
    private static final GenericContainer<?> KEYCLOAK = new GenericContainer<>("quay.io/keycloak/keycloak:26.8.1")
            .withCopyFileToContainer(
                    MountableFile.forHostPath(REALM.resolve("driftwatch-realm.json")),
                    "/opt/keycloak/data/import/driftwatch-realm.json")
            .withEnv("KC_HTTP_RELATIVE_PATH", "/auth")
            .withEnv("KC_HEALTH_ENABLED", "true")
            .withCommand("start-dev", "--import-realm")
            .withExposedPorts(8080, 9000)
            .waitingFor(Wait.forHttp("/auth/health/ready").forPort(9000))
            .withStartupTimeout(Duration.ofMinutes(2));

    /** Without KC_HOSTNAME, Keycloak issues tokens for the URL it was reached at: the mapped port. */
    private static String issuer() {
        return "http://localhost:" + KEYCLOAK.getMappedPort(8080) + "/auth/realms/driftwatch";
    }

    @DynamicPropertySource
    static void keycloak(DynamicPropertyRegistry registry) {
        registry.add("driftwatch.oidc.issuer", KeycloakIT::issuer);
        // Left unset, so the default derived from the issuer is what finds Keycloak's JWKS endpoint.
    }

    @Override
    protected String viewerToken() throws Exception {
        return clientCredentialsToken("driftwatch-smoke-viewer", "smoke-viewer-demo-secret");
    }

    @Override
    protected String adminToken() throws Exception {
        return clientCredentialsToken("driftwatch-smoke-admin", "smoke-admin-demo-secret");
    }

    /** Keycloak stamps real time into its tokens, so the API's clock must read real time too. */
    @BeforeEach
    void useRealTime() {
        clock.set(Instant.now());
    }

    @Test
    void smokeClientTokensAreAuthorisedByRole() throws Exception {
        String viewer = viewerToken();
        String admin = adminToken();
        Agent agent = enroll("machine-0001");
        ingest(agent, ScanReports.scan(clock.instant()).result(ROOT_LOGIN, "high", "PASS"));

        mvc.perform(get("/api/v1/hosts").with(bearer(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1));
        mvc.perform(delete("/api/v1/hosts/{id}", agent.hostId()).with(bearer(viewer)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/hosts").with(bearer(admin))).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/hosts/{id}", agent.hostId()).with(bearer(admin)))
                .andExpect(status().isNoContent());

        submitScan(agent.token(), ScanReports.scan(clock.instant()).result(ROOT_LOGIN, "high", "PASS"))
                .andExpect(status().isUnauthorized());
        submitScan(admin, ScanReports.scan(clock.instant()).result(ROOT_LOGIN, "high", "PASS"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theRealmIssuesTheClaimsTheApiReliesOn() throws Exception {
        String viewer = viewerToken();
        String admin = adminToken();

        assertThat(JsonPath.<Object>read(claims(viewer), "$.aud").toString()).contains("driftwatch-api");
        assertThat(JsonPath.<List<String>>read(claims(viewer), "$.realm_access.roles"))
                .contains("viewer")
                .doesNotContain("admin");
        assertThat(JsonPath.<List<String>>read(claims(admin), "$.realm_access.roles"))
                .contains("admin", "viewer");
        assertThat(JsonPath.<String>read(claims(admin), "$.iss")).isEqualTo(issuer());
    }

    private static String clientCredentialsToken(String clientId, String secret) throws Exception {
        String form = form(Map.of("grant_type", "client_credentials", "client_id", clientId, "client_secret", secret));
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(issuer() + "/protocol/openid-connect/token"))
                            .header(HttpHeaders.CONTENT_TYPE, "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(form))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            return JsonPath.read(response.body(), "$.access_token");
        }
    }

    private static String claims(String jwt) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    private static String form(Map<String, String> fields) {
        return fields.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .reduce((a, b) -> a + "&" + b)
                .orElseThrow();
    }
}
