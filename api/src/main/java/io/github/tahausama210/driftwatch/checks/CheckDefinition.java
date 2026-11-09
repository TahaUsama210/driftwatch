package io.github.tahausama210.driftwatch.checks;

/** Catalog metadata for a check, as reported by an agent. */
public record CheckDefinition(String id, String title, Category category, Severity severity) {}
