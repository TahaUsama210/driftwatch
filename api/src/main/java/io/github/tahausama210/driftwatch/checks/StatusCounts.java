package io.github.tahausama210.driftwatch.checks;

import java.util.Collection;

/** How many checks (or hosts) are in each {@link CheckStatus}. */
public record StatusCounts(int pass, int fail, int error, int notApplicable) {

    public static StatusCounts tally(Collection<CheckStatus> statuses) {
        int pass = 0;
        int fail = 0;
        int error = 0;
        int notApplicable = 0;
        for (CheckStatus status : statuses) {
            switch (status) {
                case PASS -> pass++;
                case FAIL -> fail++;
                case ERROR -> error++;
                case NOT_APPLICABLE -> notApplicable++;
            }
        }
        return new StatusCounts(pass, fail, error, notApplicable);
    }

    /** {@code FAIL} plus {@code ERROR}. */
    public int failing() {
        return fail + error;
    }
}
