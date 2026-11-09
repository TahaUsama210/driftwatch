package io.github.tahausama210.driftwatch.support;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import tools.jackson.databind.json.JsonMapper;

/** Builds scan report request bodies as plain JSON, independent of the production DTOs. */
public final class ScanReports {

    // A few catalog checks (docs/CONTRACTS.md) with their severities.
    public static final String ROOT_LOGIN = "ssh.permit_root_login"; // high
    public static final String UID0_ONLY_ROOT = "accounts.uid0_only_root"; // critical
    public static final String ASLR = "kernel.aslr_enabled"; // high
    public static final String IP_FORWARDING = "network.ip_forwarding_disabled"; // medium
    public static final String CRONTAB = "filesystem.crontab_permissions"; // low

    private ScanReports() {}

    public static Builder scan(Instant finishedAt) {
        return new Builder(finishedAt);
    }

    public static final class Builder {

        private UUID scanId = UUID.randomUUID();
        private String hostname;
        private String agentVersion = "0.1.0";
        private Instant startedAt;
        private Instant finishedAt;
        private final List<Map<String, Object>> results = new ArrayList<>();

        private Builder(Instant finishedAt) {
            this.finishedAt = finishedAt;
            this.startedAt = finishedAt.minusSeconds(3);
        }

        public Builder scanId(UUID value) {
            scanId = value;
            return this;
        }

        public Builder hostname(String value) {
            hostname = value;
            return this;
        }

        /** Uses {@code value} unless a hostname was set explicitly; a report updates the host's facts. */
        public Builder hostnameIfUnset(String value) {
            if (hostname == null) {
                hostname = value;
            }
            return this;
        }

        public Builder agentVersion(String value) {
            agentVersion = value;
            return this;
        }

        public Builder startedAt(Instant value) {
            startedAt = value;
            return this;
        }

        public Builder finishedAt(Instant value) {
            finishedAt = value;
            return this;
        }

        public Builder result(String checkId, String severity, String status) {
            return result(checkId, severity, status, null);
        }

        public Builder result(String checkId, String severity, String status, String evidence) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("checkId", checkId);
            result.put("title", "Title of " + checkId);
            result.put("category", checkId.contains(".") ? checkId.substring(0, checkId.indexOf('.')) : "ssh");
            result.put("severity", severity);
            result.put("status", status);
            result.put("expected", "expected value");
            result.put("actual", "actual value");
            result.put("evidence", evidence);
            results.add(result);
            return this;
        }

        /** Adds a raw result entry, for tests that need invalid values. */
        public Builder rawResult(Map<String, Object> result) {
            results.add(result);
            return this;
        }

        public UUID scanId() {
            return scanId;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("scanId", scanId.toString());
            report.put("agentVersion", agentVersion);
            report.put("startedAt", startedAt.toString());
            report.put("finishedAt", finishedAt.toString());
            report.put(
                    "host",
                    Map.of(
                            "hostname",
                            hostname == null ? "web-01" : hostname,
                            "os",
                            Map.of("id", "ubuntu", "version", "24.04", "prettyName", "Ubuntu 24.04.3 LTS"),
                            "kernel",
                            "6.8.0-48-generic",
                            "arch",
                            "amd64"));
            report.put("results", results);
            return report;
        }

        public String toJson(JsonMapper json) {
            return json.writeValueAsString(toMap());
        }
    }
}
