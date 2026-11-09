package io.github.tahausama210.driftwatch.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlassian.oai.validator.report.ValidationReport;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** Proves the contract check can fail, so a passing integration suite actually means something. */
class OpenApiContractTest {

    @Test
    void aResponseMatchingTheContractPasses() throws Exception {
        assertThat(validate("/api/v1/fleet/trend", 200, "application/json", "{\"bucketMinutes\":60,\"points\":[]}")
                        .hasErrors())
                .isFalse();
    }

    @Test
    void aMissingRequiredPropertyFails() throws Exception {
        assertThat(validate("/api/v1/fleet/trend", 200, "application/json", "{\"points\":[]}")
                        .hasErrors())
                .isTrue();
    }

    @Test
    void aWrongTypeFails() throws Exception {
        assertThat(validate("/api/v1/fleet/trend", 200, "application/json", "{\"bucketMinutes\":\"60\",\"points\":[]}")
                        .hasErrors())
                .isTrue();
    }

    @Test
    void anUndocumentedPropertyFails() throws Exception {
        assertThat(validate(
                                "/api/v1/fleet/trend",
                                200,
                                "application/json",
                                "{\"bucketMinutes\":60,\"points\":[],\"internal\":true}")
                        .hasErrors())
                .isTrue();
    }

    @Test
    void anUndocumentedStatusFails() throws Exception {
        assertThat(validate("/api/v1/fleet/summary", 500, "application/problem+json", problem(500))
                        .hasErrors())
                .isTrue();
    }

    @Test
    void anErrorWithTheWrongMediaTypeFails() throws Exception {
        assertThat(validate("/api/v1/fleet/trend", 400, "application/json", problem(400))
                        .hasErrors())
                .isTrue();
        assertThat(validate("/api/v1/fleet/trend", 400, "application/problem+json", problem(400))
                        .hasErrors())
                .isFalse();
    }

    private static ValidationReport validate(String path, int status, String contentType, String body)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(status);
        response.setContentType(contentType);
        response.getWriter().write(body);
        return OpenApiContract.validate(request, response);
    }

    private static String problem(int status) {
        return "{\"type\":\"about:blank\",\"title\":\"Error\",\"status\":" + status + "}";
    }
}
