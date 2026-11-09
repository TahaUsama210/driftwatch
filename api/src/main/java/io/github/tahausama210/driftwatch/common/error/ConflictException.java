package io.github.tahausama210.driftwatch.common.error;

/** The request conflicts with existing state; rendered as {@code 409}. The message becomes the problem detail. */
public class ConflictException extends RuntimeException {

    public ConflictException(String detail) {
        super(detail);
    }
}
