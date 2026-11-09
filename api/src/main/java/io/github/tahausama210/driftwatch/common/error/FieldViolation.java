package io.github.tahausama210.driftwatch.common.error;

/**
 * One entry of a validation problem's {@code errors} array.
 *
 * @param field path of the offending value, e.g. {@code results[3].checkId} or a query parameter name
 * @param message what is wrong with it
 */
public record FieldViolation(String field, String message) {}
