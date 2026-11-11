package io.github.tahausama210.driftwatch.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

/**
 * The full application on a random port, a Testcontainers Postgres, a controllable clock, and
 * contract validation of every MockMvc response; {@link #resetState()} isolates tests from each
 * other. Subclasses decide where dashboard tokens come from (the OIDC settings and
 * {@link #viewerToken()}/{@link #adminToken()}): almost every test extends {@link IntegrationTest},
 * which shares one context and one in-test identity provider.
 *
 * <p>{@link #getJson} sends a viewer access token, since every read endpoint requires one; the
 * tests of authentication itself build their requests explicitly.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "driftwatch.enrollment-token=" + ApplicationTest.ENROLLMENT_TOKEN,
            "driftwatch.cors-origins=" + ApplicationTest.DASHBOARD_ORIGIN,
            // Tests refresh the gauges explicitly; keep the scheduler out of the way.
            "driftwatch.metrics-refresh-interval=PT1H"
        })
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, IntegrationTestConfiguration.class})
public abstract class ApplicationTest {

    public static final String ENROLLMENT_TOKEN = "test-enrollment-token-0123456789";
    public static final String DASHBOARD_ORIGIN = "http://localhost:5180";
    /** What the clock reads at the start of every test. */
    public static final Instant NOW = Instant.parse("2026-11-03T12:00:00Z");

    /** The result of enrolling a host. */
    public record Agent(UUID hostId, String token, String hostname) {}

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected JsonMapper json;

    @BeforeEach
    void resetState() {
        jdbc.sql("TRUNCATE drift_events, scan_results, scans, hosts, checks").update();
        clock.set(NOW);
    }

    protected Agent enroll(String machineId) throws Exception {
        return enroll(machineId, machineId + ".lab");
    }

    protected Agent enroll(String machineId, String hostname) throws Exception {
        String body = enrollRequest(machineId, hostname)
                .andExpect(status().is2xxSuccessful())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return new Agent(
                UUID.fromString(JsonPath.read(body, "$.hostId")), JsonPath.read(body, "$.agentToken"), hostname);
    }

    protected ResultActions enrollRequest(String machineId, String hostname) throws Exception {
        return mvc.perform(post("/api/v1/agents/enroll")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ENROLLMENT_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(enrollBody(machineId, hostname))));
    }

    protected static Map<String, Object> enrollBody(String machineId, String hostname) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("machineId", machineId);
        body.put("agentVersion", "0.1.0");
        body.put("hostname", hostname);
        body.put("os", Map.of("id", "ubuntu", "version", "24.04", "prettyName", "Ubuntu 24.04.3 LTS"));
        body.put("kernel", "6.8.0-48-generic");
        body.put("arch", "amd64");
        return body;
    }

    protected ResultActions submitScan(String token, ScanReports.Builder report) throws Exception {
        return submitScan(token, report.toJson(json));
    }

    protected ResultActions submitScan(String token, String body) throws Exception {
        return mvc.perform(post("/api/v1/scans")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /** Submits a scan, reporting the agent's hostname unless set, that must be accepted as new. */
    protected UUID ingest(Agent agent, ScanReports.Builder report) throws Exception {
        submitScan(agent.token(), report.hostnameIfUnset(agent.hostname())).andExpect(status().isCreated());
        return report.scanId();
    }

    protected ResultActions getJson(String path, Object... uriVariables) throws Exception {
        return mvc.perform(
                get(path, uriVariables).accept(MediaType.APPLICATION_JSON).with(asViewer()));
    }

    /** An access token with the {@code viewer} role, valid now. */
    protected abstract String viewerToken() throws Exception;

    /** An access token with the {@code admin} role, valid now. */
    protected abstract String adminToken() throws Exception;

    protected RequestPostProcessor asViewer() throws Exception {
        return bearer(viewerToken());
    }

    protected RequestPostProcessor asAdmin() throws Exception {
        return bearer(adminToken());
    }

    protected static RequestPostProcessor bearer(String token) {
        return request -> {
            request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
            return request;
        };
    }

    /**
     * Asserts the exact property names of the JSON object at {@code jsonPath}. The contract check
     * cannot reject undocumented properties in the allOf responses (see {@link OpenApiContract}), so
     * the tests of those responses pin their shape with this.
     */
    protected static ResultMatcher hasPropertyNames(String jsonPath, String... names) {
        return result -> {
            Map<String, Object> object = JsonPath.read(result.getResponse().getContentAsString(), jsonPath);
            assertThat(object.keySet()).as(jsonPath).containsExactlyInAnyOrder(names);
        };
    }
}
