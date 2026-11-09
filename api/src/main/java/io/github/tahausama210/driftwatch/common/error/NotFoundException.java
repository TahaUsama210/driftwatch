package io.github.tahausama210.driftwatch.common.error;

/** The requested resource does not exist; rendered as {@code 404}. The message becomes the problem detail. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String detail) {
        super(detail);
    }
}
