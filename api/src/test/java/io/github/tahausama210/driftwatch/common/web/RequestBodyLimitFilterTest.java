package io.github.tahausama210.driftwatch.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.FilterChain;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

class RequestBodyLimitFilterTest {

    private static final int LIMIT = 16;

    private final List<Exception> resolved = new ArrayList<>();
    private final HandlerExceptionResolver resolver = (request, response, handler, ex) -> {
        resolved.add(ex);
        response.setStatus(413);
        return new ModelAndView();
    };
    private final RequestBodyLimitFilter filter = new RequestBodyLimitFilter(LIMIT, resolver);

    @Test
    void aDeclaredLengthOverTheLimitIsRejectedWithoutCallingTheChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/scans");
        request.setContent(new byte[LIMIT + 1]);
        FilterChain chain = (req, res) -> {
            throw new AssertionError("chain must not be invoked");
        };

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(resolved).singleElement().isInstanceOf(RequestBodyTooLargeException.class);
    }

    @Test
    void aBodyAtTheLimitPassesThroughUnchanged() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/scans");
        request.setContent("0123456789abcdef".getBytes());
        List<String> seen = new ArrayList<>();

        filter.doFilter(
                request,
                new MockHttpServletResponse(),
                (req, res) -> seen.add(new String(req.getInputStream().readAllBytes())));

        assertThat(seen).containsExactly("0123456789abcdef");
        assertThat(resolved).isEmpty();
    }

    @Test
    void aBodyWithoutContentLengthFailsOnceReadingCrossesTheLimit() throws Exception {
        MockHttpServletRequest request = new UndeclaredLengthRequest(new byte[LIMIT + 1]);

        filter.doFilter(
                request,
                new MockHttpServletResponse(),
                (req, res) -> assertThatThrownBy(() -> req.getInputStream().readAllBytes())
                        .isInstanceOf(RequestBodyTooLargeException.class));
    }

    @Test
    void theReaderIsLimitedToo() throws Exception {
        MockHttpServletRequest request = new UndeclaredLengthRequest(new byte[LIMIT + 1]);

        filter.doFilter(
                request,
                new MockHttpServletResponse(),
                (req, res) -> assertThatThrownBy(() -> req.getReader().read(new char[64]))
                        .isInstanceOf(RequestBodyTooLargeException.class));
    }

    /** Simulates chunked transfer encoding, where the container reports no length. */
    private static final class UndeclaredLengthRequest extends MockHttpServletRequest {

        UndeclaredLengthRequest(byte[] content) {
            super("POST", "/api/v1/scans");
            setContent(content);
        }

        @Override
        public long getContentLengthLong() {
            return -1;
        }

        @Override
        public int getContentLength() {
            return -1;
        }
    }
}
