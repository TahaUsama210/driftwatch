package io.github.tahausama210.driftwatch.common.error;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Error paths that the running application cannot easily be driven into. */
class GlobalExceptionHandlerTest {

    @RestController
    static class FailingController {

        @GetMapping("/denied")
        void denied() {
            throw new AccessDeniedException("internal reason");
        }

        @GetMapping("/broken")
        void broken() {
            throw new IllegalStateException("SELECT * FROM secrets failed at Foo.java:42");
        }

        @GetMapping("/credential-store-down")
        void credentialStoreDown() {
            throw new AuthenticationServiceException("connection refused");
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new FailingController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void accessDeniedIsAForbiddenProblem() throws Exception {
        mvc.perform(get("/denied"))
                .andExpect(status().isForbidden())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(content().string(not(containsString("internal reason"))));
    }

    @Test
    void unexpectedExceptionsAreGenericServerErrorsThatLeakNothing() throws Exception {
        mvc.perform(get("/broken"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred."))
                .andExpect(content().string(not(containsString("SELECT"))))
                .andExpect(content().string(not(containsString("Foo.java"))));
    }

    @Test
    void anUnavailableCredentialStoreIsARetryableServiceUnavailableNotAnAuthenticationFailure() throws Exception {
        mvc.perform(get("/credential-store-down"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(content().string(not(containsString("connection refused"))));
    }
}
