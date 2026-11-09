package io.github.tahausama210.driftwatch.support;

import static com.atlassian.oai.validator.whitelist.rule.WhitelistRules.allOf;
import static com.atlassian.oai.validator.whitelist.rule.WhitelistRules.anyOf;
import static com.atlassian.oai.validator.whitelist.rule.WhitelistRules.messageHasKey;
import static com.atlassian.oai.validator.whitelist.rule.WhitelistRules.pathContainsRegexp;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.Response;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.LevelResolver;
import com.atlassian.oai.validator.report.SimpleValidationReportFormat;
import com.atlassian.oai.validator.report.ValidationReport;
import com.atlassian.oai.validator.whitelist.ValidationErrorsWhitelist;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultMatcher;

/**
 * Validates real responses against {@code contracts/openapi.yaml}. The integration tests install
 * {@link #conformsToContract()} on every MockMvc request, so any response whose status, media type
 * or body is not allowed by the contract fails the build.
 *
 * <p>Only responses are validated: several tests send invalid requests on purpose. Requests to
 * paths outside the contract (actuator, OpenAPI docs) find no operation and are not validated.
 */
public final class OpenApiContract {

    static final Path SPEC = Path.of("..", "contracts", "openapi.yaml");

    private static final OpenApiInteractionValidator VALIDATOR = OpenApiInteractionValidator.createForSpecificationUrl(
                    specUrl())
            .withLevelResolver(LevelResolver.create()
                    .withLevel("validation.request", ValidationReport.Level.IGNORE)
                    .build())
            .withWhitelist(ValidationErrorsWhitelist.create()
                    // The validator treats unspecified additionalProperties as false and then applies it
                    // to each allOf branch separately, which rejects every valid allOf response (and its
                    // withResolveCombinators option drops the merged properties for OpenAPI 3.1). The
                    // three allOf responses still get type, required and enum checks; the integration
                    // tests assert their exact property sets with IntegrationTest#hasPropertyNames.
                    .withRule(
                            "allOf responses (validator limitation)",
                            allOf(
                                    messageHasKey("validation.response.body.schema.additionalProperties"),
                                    anyOf(
                                            pathContainsRegexp("^/api/v1/scans/\\{"),
                                            pathContainsRegexp("^/api/v1/checks/\\{"),
                                            pathContainsRegexp("^/api/v1/events$")))))
            .build();

    private OpenApiContract() {}

    public static ResultMatcher conformsToContract() {
        return result -> {
            ValidationReport report = validate(result.getRequest(), result.getResponse());
            if (report.hasErrors()) {
                throw new AssertionError("Response violates contracts/openapi.yaml for "
                        + result.getRequest().getMethod() + " "
                        + result.getRequest().getRequestURI() + ":\n"
                        + SimpleValidationReportFormat.getInstance().apply(report));
            }
        };
    }

    static ValidationReport validate(MockHttpServletRequest request, MockHttpServletResponse response)
            throws UnsupportedEncodingException {
        return VALIDATOR.validate(toRequest(request), toResponse(response));
    }

    private static Request toRequest(MockHttpServletRequest request) {
        SimpleRequest.Builder builder = new SimpleRequest.Builder(request.getMethod(), request.getRequestURI(), false);
        request.getParameterMap().forEach(builder::withQueryParam);
        for (String name : Collections.list(request.getHeaderNames())) {
            builder.withHeader(name, Collections.list(request.getHeaders(name)));
        }
        return builder.build();
    }

    private static Response toResponse(MockHttpServletResponse response) throws UnsupportedEncodingException {
        SimpleResponse.Builder builder = SimpleResponse.Builder.status(response.getStatus());
        for (String name : response.getHeaderNames()) {
            builder.withHeader(name, response.getHeaders(name));
        }
        String body = response.getContentAsString(StandardCharsets.UTF_8);
        return body.isEmpty() ? builder.build() : builder.withBody(body).build();
    }

    private static String specUrl() {
        if (!Files.isRegularFile(SPEC)) {
            throw new IllegalStateException("Contract not found at " + SPEC.toAbsolutePath()
                    + "; the tests must run from the api/ directory of the repository");
        }
        return SPEC.toAbsolutePath().normalize().toUri().toString();
    }
}
