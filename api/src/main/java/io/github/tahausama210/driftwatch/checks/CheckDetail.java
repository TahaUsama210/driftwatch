package io.github.tahausama210.driftwatch.checks;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.util.List;

/** A check and its status on every host whose latest scan includes it; failing hosts first. */
public record CheckDetail(@JsonUnwrapped CheckSummary check, List<CheckHostStatus> hosts) {}
