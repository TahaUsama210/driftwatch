package io.github.tahausama210.driftwatch.enrollment;

import static io.github.tahausama210.driftwatch.support.ScanReports.ROOT_LOGIN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.github.tahausama210.driftwatch.support.IntegrationTest;
import io.github.tahausama210.driftwatch.support.ScanReports;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

class EnrollmentIT extends IntegrationTest {

    @Test
    void newHostIsCreatedWithAPrefixedTokenAndOnlyTheTokenHashIsStored() throws Exception {
        String body = enrollRequest("machine-0001", "web-01.lab")
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, startsWith("/api/v1/hosts/")))
                .andExpect(jsonPath("$.hostId").isString())
                .andExpect(jsonPath("$.agentToken").value(matchesPattern("dwa_[A-Za-z0-9_-]{43}")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        String token = JsonPath.read(body, "$.agentToken");
        Map<String, Object> stored = jdbc.sql("SELECT * FROM hosts").query().singleRow();
        assertThat(stored)
                .containsEntry("machine_id", "machine-0001")
                .containsEntry("hostname", "web-01.lab")
                .doesNotContainValue(token);
        assertThat((String) stored.get("token_hash")).hasSize(64).doesNotContain(token);
    }

    @Test
    void reEnrollingKeepsTheHostRotatesTheTokenAndRevokesTheOldOne() throws Exception {
        Agent first = enroll("machine-0001", "web-01.lab");
        clock.advance(Duration.ofMinutes(1));

        String body = enrollRequest("machine-0001", "web-01-renamed.lab")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hostId").value(first.hostId().toString()))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String newToken = JsonPath.read(body, "$.agentToken");

        assertThat(newToken).isNotEqualTo(first.token());
        getJson("/api/v1/hosts/{id}", first.hostId())
                .andExpect(jsonPath("$.hostname").value("web-01-renamed.lab"))
                .andExpect(jsonPath("$.enrolledAt").value(NOW.toString()))
                .andExpect(jsonPath("$.lastSeenAt")
                        .value(NOW.plus(Duration.ofMinutes(1)).toString()));

        ScanReports.Builder scan = ScanReports.scan(clock.instant()).result(ROOT_LOGIN, "high", "PASS");
        submitScan(first.token(), scan)
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        submitScan(newToken, scan).andExpect(status().isCreated());
    }

    @Test
    void missingEnrollmentTokenIsUnauthorized() throws Exception {
        mvc.perform(post("/api/v1/agents/enroll")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(enrollBody("machine-0001", "web-01"))))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").isString())
                .andExpect(jsonPath("$.instance").value("/api/v1/agents/enroll"));
        assertThat(hostCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "Bearer wrong-enrollment-token-0123456789",
                "Bearer " + ENROLLMENT_TOKEN + "x",
                "Basic dXNlcjpwYXNz",
                "Bearer",
                "Bearer two words"
            })
    void wrongOrMalformedEnrollmentTokenIsUnauthorized(String authorization) throws Exception {
        mvc.perform(post("/api/v1/agents/enroll")
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(enrollBody("machine-0001", "web-01"))))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
        assertThat(hostCount()).isZero();
    }

    @Test
    void anAgentTokenCannotEnroll() throws Exception {
        Agent agent = enroll("machine-0001");

        mvc.perform(post("/api/v1/agents/enroll")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + agent.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(enrollBody("machine-0002", "web-02"))))
                .andExpect(status().isUnauthorized());
        assertThat(hostCount()).isOne();
    }

    @Test
    void invalidEnrollmentIsRejectedWithFieldPaths() throws Exception {
        Map<String, Object> body = enrollBody("short", "");
        body.put("os", Map.of("id", "ubuntu"));
        body.remove("kernel");

        mvc.perform(post("/api/v1/agents/enroll")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ENROLLMENT_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors", hasSize(4)))
                .andExpect(jsonPath("$.errors[*].field")
                        .value(contains("hostname", "kernel", "machineId", "os.prettyName")));
        assertThat(hostCount()).isZero();
    }

    private long hostCount() {
        return jdbc.sql("SELECT count(*) FROM hosts").query(Long.class).single();
    }
}
