package io.github.tahausama210.driftwatch;

import static io.github.tahausama210.driftwatch.support.ScanReports.ROOT_LOGIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.tahausama210.driftwatch.common.web.RequestIdFilter;
import io.github.tahausama210.driftwatch.support.IntegrationTest;
import io.github.tahausama210.driftwatch.support.ScanReports;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** Cross-cutting behaviour: access rules, probes, CORS, request ids, and the real HTTP stack. */
class PlatformIT extends IntegrationTest {

    @LocalServerPort
    private int port;

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/actuator/health",
                "/actuator/health/liveness",
                "/actuator/health/readiness",
                "/actuator/prometheus",
                "/v3/api-docs",
                "/swagger-ui/index.html"
            })
    void operationalEndpointsArePublic(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isOk());
    }

    @Test
    void readinessReportsUpWithoutLeakingComponentDetails() throws Exception {
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void theRunningAppServesItsOpenApiDocument() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.paths['/api/v1/scans'].post.operationId").value("submitScan"));
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/env", "/actuator/beans", "/internal"})
    void everythingOutsideTheAllowListIsDenied(String path) throws Exception {
        mvc.perform(get(path))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void writesToReadEndpointsAreDenied() throws Exception {
        mvc.perform(post("/api/v1/hosts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/hosts")
                        .with(asAdmin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        mvc.perform(delete("/api/v1/hosts/{id}", UUID.randomUUID())).andExpect(status().isUnauthorized());
    }

    @Test
    void unknownApiPathsNeedATokenAndAreThenNotFoundProblems() throws Exception {
        mvc.perform(get("/api/v1/does-not-exist")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/does-not-exist").with(asViewer()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.detail").value("No endpoint exists at this path."));
    }

    @Test
    void urlsTheFirewallRejectsAreBadRequestProblems() throws Exception {
        mvc.perform(get("/api/v1/hosts;jsessionid=abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.detail").value("The request URL is not allowed."));

        // MockMvc normalises "//", so the real server is needed for that case.
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1//hosts"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE))
                    .hasValue(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            assertThat(response.body()).contains("\"detail\":\"The request URL is not allowed.\"");
        }
    }

    @Test
    void aSafeCallerRequestIdIsEchoedAndAnUnsafeOneReplaced() throws Exception {
        mvc.perform(get("/api/v1/fleet/summary").with(asViewer()).header(RequestIdFilter.HEADER, "trace-abc.123"))
                .andExpect(header().string(RequestIdFilter.HEADER, "trace-abc.123"));
        mvc.perform(get("/api/v1/fleet/summary")
                        .with(asViewer())
                        .header(RequestIdFilter.HEADER, "bad id\nInjected: yes"))
                .andExpect(header().string(RequestIdFilter.HEADER, matchesPattern("[0-9a-f-]{36}")));
    }

    @Test
    void theDashboardOriginMayCallWithItsBearerTokenCrossOrigin() throws Exception {
        mvc.perform(options("/api/v1/hosts")
                        .header(HttpHeaders.ORIGIN, DASHBOARD_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, DASHBOARD_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "authorization"));
        mvc.perform(options("/api/v1/hosts/{id}", UUID.randomUUID())
                        .header(HttpHeaders.ORIGIN, DASHBOARD_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "DELETE"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/hosts").with(asViewer()).header(HttpHeaders.ORIGIN, DASHBOARD_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, RequestIdFilter.HEADER));
    }

    @Test
    void otherOriginsAndCrossOriginWritesAreRefused() throws Exception {
        mvc.perform(options("/api/v1/hosts")
                        .header(HttpHeaders.ORIGIN, "https://evil.example")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        mvc.perform(options("/api/v1/scans")
                        .header(HttpHeaders.ORIGIN, DASHBOARD_ORIGIN)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    /**
     * MockMvc always knows the body length, so the streaming half of the body limit is exercised over
     * real HTTP: a chunked upload carries no Content-Length and must be cut off while it is read.
     */
    @Test
    void anOversizedChunkedBodyIsCutOffWhileStreaming() throws Exception {
        Agent agent = enroll("machine-0001");
        byte[] oversized = ("{\"agentVersion\":\"" + "a".repeat(1100 * 1024) + "\"}").getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/scans"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + agent.token())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(oversized)))
                .build();

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).isEqualTo(413);
            assertThat(response.headers().firstValue(HttpHeaders.CONTENT_TYPE))
                    .hasValue(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            assertThat(response.body()).contains("\"status\":413");
        }
    }

    @Test
    void aScanWithinTheLimitIsAcceptedOverRealHttp() throws Exception {
        Agent agent = enroll("machine-0001");
        String body = ScanReports.scan(NOW).result(ROOT_LOGIN, "high", "PASS").toJson(json);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/scans"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + agent.token())
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        try (HttpClient client = HttpClient.newHttpClient()) {
            assertThat(client.send(request, HttpResponse.BodyHandlers.ofString())
                            .statusCode())
                    .isEqualTo(201);
        }
    }
}
