package io.github.tahausama210.driftwatch.hosts;

import org.jspecify.annotations.Nullable;

/**
 * Filters, ordering and paging for the host list.
 *
 * @param query case-insensitive substring of the hostname
 * @param status only hosts in this status
 */
public record HostSearch(
        @Nullable String query,
        @Nullable HostStatus status,
        HostSortField sort,
        SortDirection direction,
        int page,
        int size) {

    long offset() {
        return (long) page * size;
    }
}
