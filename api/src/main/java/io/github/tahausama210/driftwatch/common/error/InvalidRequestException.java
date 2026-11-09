package io.github.tahausama210.driftwatch.common.error;

import java.util.List;

/**
 * A request that is well-formed but violates a rule checked outside Bean Validation; rendered as a
 * {@code 400} validation problem.
 */
public class InvalidRequestException extends RuntimeException {

    private final List<FieldViolation> violations;

    public InvalidRequestException(FieldViolation violation) {
        super(violation.field() + " " + violation.message());
        this.violations = List.of(violation);
    }

    public List<FieldViolation> violations() {
        return violations;
    }
}
