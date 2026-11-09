package io.github.tahausama210.driftwatch.common.web;

import org.springframework.core.Ordered;

/**
 * Order of the application's servlet filters, all of which run ahead of Spring Security:
 *
 * <ol>
 *   <li>request id, so every later log line carries it;
 *   <li>ingest outcome metrics, which must observe the final status of rejected submissions,
 *       including {@code 401} from security and {@code 413} from the body limit;
 *   <li>request body limit, so oversized bodies are refused before any other work.
 * </ol>
 */
public final class FilterOrder {

    public static final int REQUEST_ID = Ordered.HIGHEST_PRECEDENCE;
    public static final int INGEST_OUTCOME_METRICS = Ordered.HIGHEST_PRECEDENCE + 1;
    public static final int REQUEST_BODY_LIMIT = Ordered.HIGHEST_PRECEDENCE + 2;

    private FilterOrder() {}
}
