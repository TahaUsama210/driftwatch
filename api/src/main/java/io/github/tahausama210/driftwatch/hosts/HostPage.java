package io.github.tahausama210.driftwatch.hosts;

import java.util.List;

/** One page of hosts; {@code totalItems} counts all hosts matching the filters. */
public record HostPage(List<Host> items, int page, int size, long totalItems) {}
