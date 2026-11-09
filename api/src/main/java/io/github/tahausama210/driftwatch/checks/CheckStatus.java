package io.github.tahausama210.driftwatch.checks;

/** Outcome of one check on one host. */
public enum CheckStatus {
    PASS,
    FAIL,
    /** The check could not be evaluated; for scoring it counts as failing. */
    ERROR,
    /** The thing being checked does not exist on the host; excluded from scoring. */
    NOT_APPLICABLE;

    /** {@code FAIL} and {@code ERROR}: an unverifiable control is not a compliant one. */
    public boolean isFailing() {
        return this == FAIL || this == ERROR;
    }
}
