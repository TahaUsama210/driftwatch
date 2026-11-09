package io.github.tahausama210.driftwatch.common.web;

import java.io.IOException;

/**
 * The request body exceeds the configured limit. An {@link IOException} because it is raised from
 * inside the request {@code InputStream} when a body without a {@code Content-Length} grows too large.
 */
public class RequestBodyTooLargeException extends IOException {

    public RequestBodyTooLargeException(long limitBytes) {
        super("The request body must not exceed " + limitBytes + " bytes.");
    }
}
