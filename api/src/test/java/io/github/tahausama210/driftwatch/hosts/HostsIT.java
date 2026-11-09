package io.github.tahausama210.driftwatch.hosts;

import static io.github.tahausama210.driftwatch.support.ScanReports.ASLR;
import static io.github.tahausama210.driftwatch.support.ScanReports.CRONTAB;
import static io.github.tahausama210.driftwatch.support.ScanReports.IP_FORWARDING;
import static io.github.tahausama210.driftwatch.support.ScanReports.ROOT_LOGIN;
import static io.github.tahausama210.driftwatch.support.ScanReports.UID0_ONLY_ROOT;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.tahausama210.driftwatch.support.IntegrationTest;
import io.github.tahausama210.driftwatch.support.ScanReports;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class HostsIT extends IntegrationTest {

    @Test
    void hostsAreListedByHostnameWithPaging() throws Exception {
        enroll("machine-000c", "charlie");
        enroll("machine-000a", "alpha");
        enroll("machine-000b", "bravo");

        getJson("/api/v1/hosts")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].hostname").value(contains("alpha", "bravo", "charlie")))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(50))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.items[0].latestScan").value(nullValue()))
                .andExpect(jsonPath("$.items[0].os.prettyName").value("Ubuntu 24.04.3 LTS"));
        getJson("/api/v1/hosts?size=2&page=1")
                .andExpect(jsonPath("$.items[*].hostname").value(contains("charlie")))
                .andExpect(jsonPath("$.totalItems").value(3));
        getJson("/api/v1/hosts?size=2&page=7")
                .andExpect(jsonPath("$.items", empty()))
                .andExpect(jsonPath("$.totalItems").value(3));
        getJson("/api/v1/hosts?direction=desc")
                .andExpect(jsonPath("$.items[*].hostname").value(contains("charlie", "bravo", "alpha")));
    }

    @Test
    void hostsWithoutAScoreSortLastInBothDirections() throws Exception {
        ingest(enroll("machine-000a", "alpha"), ScanReports.scan(NOW).result(ROOT_LOGIN, "high", "FAIL"));
        ingest(enroll("machine-000b", "bravo"), ScanReports.scan(NOW).result(ROOT_LOGIN, "high", "PASS"));
        enroll("machine-000c", "charlie");
        ingest(enroll("machine-000d", "delta"), ScanReports.scan(NOW).result(CRONTAB, "low", "NOT_APPLICABLE"));

        getJson("/api/v1/hosts?sort=score")
                .andExpect(jsonPath("$.items[*].hostname").value(contains("alpha", "bravo", "charlie", "delta")));
        getJson("/api/v1/hosts?sort=score&direction=desc")
                .andExpect(jsonPath("$.items[*].hostname").value(contains("bravo", "alpha", "charlie", "delta")))
                .andExpect(jsonPath("$.items[0].latestScan.score").value(100.0));
    }

    @Test
    void hostsCanBeSortedByLastContact() throws Exception {
        enroll("machine-000a", "alpha");
        clock.advance(Duration.ofSeconds(10));
        enroll("machine-000b", "bravo");
        clock.advance(Duration.ofSeconds(10));
        enroll("machine-000c", "charlie");
        clock.advance(Duration.ofSeconds(10));
        ingest(enroll("machine-000a", "alpha"), ScanReports.scan(NOW).result(ROOT_LOGIN, "high", "PASS"));

        getJson("/api/v1/hosts?sort=lastSeenAt&direction=desc")
                .andExpect(jsonPath("$.items[*].hostname").value(contains("alpha", "charlie", "bravo")));
        getJson("/api/v1/hosts?sort=lastSeenAt")
                .andExpect(jsonPath("$.items[*].hostname").value(contains("bravo", "charlie", "alpha")));
    }

    @Test
    void hostnameFilterIsACaseInsensitiveLiteralSubstring() throws Exception {
        enroll("machine-0001", "Web-01.lab");
        enroll("machine-0002", "web-02.lab");
        enroll("machine-0003", "db_1.lab");
        enroll("machine-0004", "db1.lab");

        getJson("/api/v1/hosts?query=WEB")
                .andExpect(jsonPath("$.items[*].hostname").value(contains("Web-01.lab", "web-02.lab")))
                .andExpect(jsonPath("$.totalItems").value(2));
        // "_" and "%" are matched literally, not as LIKE wildcards.
        getJson("/api/v1/hosts?query=db_")
                .andExpect(jsonPath("$.items[*].hostname").value(contains("db_1.lab")));
        getJson("/api/v1/hosts?query=%25").andExpect(jsonPath("$.items", empty()));
    }

    @Test
    void hostsBecomeStaleOnceLastContactIsOlderThanStaleAfter() throws Exception {
        clock.set(NOW.minus(Duration.ofMinutes(5)).minusSeconds(1));
        enroll("machine-0001", "stale-host");
        clock.set(NOW.minus(Duration.ofMinutes(5)));
        enroll("machine-0002", "boundary-host");
        clock.set(NOW);
        Agent fresh = enroll("machine-0003", "fresh-host");

        getJson("/api/v1/hosts?status=STALE")
                .andExpect(jsonPath("$.items[*].hostname").value(contains("stale-host")))
                .andExpect(jsonPath("$.items[0].status").value("STALE"));
        getJson("/api/v1/hosts?status=ONLINE")
                .andExpect(jsonPath("$.items[*].hostname").value(contains("boundary-host", "fresh-host")))
                .andExpect(jsonPath("$.totalItems").value(2));

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        getJson("/api/v1/hosts/{id}", fresh.hostId())
                .andExpect(jsonPath("$.status").value("STALE"));
        getJson("/api/v1/hosts?status=ONLINE").andExpect(jsonPath("$.items", empty()));
    }

    @Test
    void hostStatusAndTheStatusFilterAgreeWithinTheDatabasesMicrosecondPrecision() throws Exception {
        enroll("machine-0001", "boundary-host");
        // 400 ns past the cut-off, below the microsecond precision of the stored lastSeenAt.
        clock.set(NOW.plus(Duration.ofMinutes(5)).plusNanos(400));

        getJson("/api/v1/hosts?status=ONLINE")
                .andExpect(jsonPath("$.items[*].status").value(contains("ONLINE")));
        getJson("/api/v1/hosts?status=STALE").andExpect(jsonPath("$.items", empty()));
    }

    @ParameterizedTest
    @CsvSource({
        "size=201, size",
        "size=0, size",
        "page=-1, page",
        "sort=name, sort",
        "direction=up, direction",
        "status=offline, status",
        "page=first, page"
    })
    void invalidListParametersAreRejected(String query, String field) throws Exception {
        getJson("/api/v1/hosts?" + query)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value(field));
    }

    @Test
    void hostnameQueryIsLimitedTo255Characters() throws Exception {
        getJson("/api/v1/hosts?query=" + "a".repeat(256))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("query"));
    }

    @Test
    void hostDetailIncludesTheLatestScan() throws Exception {
        Agent agent = enroll("machine-0001", "web-01");
        UUID scan = ingest(agent, ScanReports.scan(NOW).result(ROOT_LOGIN, "high", "PASS"));

        getJson("/api/v1/hosts/{id}", agent.hostId())
                .andExpect(jsonPath("$.id").value(agent.hostId().toString()))
                .andExpect(jsonPath("$.hostname").value("web-01"))
                .andExpect(jsonPath("$.os.id").value("ubuntu"))
                .andExpect(jsonPath("$.os.version").value("24.04"))
                .andExpect(jsonPath("$.kernel").value("6.8.0-48-generic"))
                .andExpect(jsonPath("$.arch").value("amd64"))
                .andExpect(jsonPath("$.agentVersion").value("0.1.0"))
                .andExpect(jsonPath("$.status").value("ONLINE"))
                .andExpect(jsonPath("$.latestScan.id").value(scan.toString()))
                .andExpect(jsonPath("$.latestScan.score").value(100.0));
    }

    @Test
    void unknownOrMalformedHostIdsAreNotFound() throws Exception {
        getJson("/api/v1/hosts/{id}", UUID.randomUUID())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not Found"));
        getJson("/api/v1/hosts/not-a-uuid").andExpect(status().isNotFound());
        getJson("/api/v1/hosts/{id}/scans", UUID.randomUUID()).andExpect(status().isNotFound());
    }

    @Test
    void scanHistoryIsNewestFirstAndLimited() throws Exception {
        Agent agent = enroll("machine-0001");
        UUID oldest = ingest(agent, ScanReports.scan(NOW.minusSeconds(30)).result(ROOT_LOGIN, "high", "PASS"));
        UUID newest = ingest(agent, ScanReports.scan(NOW.minusSeconds(10)).result(ROOT_LOGIN, "high", "PASS"));
        UUID middle = ingest(agent, ScanReports.scan(NOW.minusSeconds(20)).result(ROOT_LOGIN, "high", "PASS"));

        getJson("/api/v1/hosts/{id}/scans", agent.hostId())
                .andExpect(
                        jsonPath("$[*].id").value(contains(newest.toString(), middle.toString(), oldest.toString())));
        getJson("/api/v1/hosts/{id}/scans?limit=1", agent.hostId()).andExpect(jsonPath("$", hasSize(1)));
        getJson("/api/v1/hosts/{id}/scans?limit=501", agent.hostId())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("limit"));
    }

    @Test
    void scanDetailListsResultsByCategoryThenCheckId() throws Exception {
        Agent agent = enroll("machine-0001", "web-01");
        UUID scan = ingest(
                agent,
                ScanReports.scan(NOW)
                        .result(ROOT_LOGIN, "high", "FAIL", "/etc/ssh/sshd_config:32: PermitRootLogin yes")
                        .result("ssh.idle_timeout", "low", "PASS")
                        .result(IP_FORWARDING, "medium", "PASS")
                        .result(ASLR, "high", "PASS")
                        .result(UID0_ONLY_ROOT, "critical", "PASS")
                        .result(CRONTAB, "low", "NOT_APPLICABLE"));

        getJson("/api/v1/scans/{id}", scan)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(scan.toString()))
                .andExpect(jsonPath("$.hostname").value("web-01"))
                .andExpect(jsonPath("$.counts.pass").value(4))
                .andExpect(jsonPath("$.results[*].checkId")
                        .value(contains(UID0_ONLY_ROOT, CRONTAB, ASLR, IP_FORWARDING, "ssh.idle_timeout", ROOT_LOGIN)))
                .andExpect(jsonPath("$.results[5].evidence").value("/etc/ssh/sshd_config:32: PermitRootLogin yes"))
                .andExpect(jsonPath("$.results[5].title").value("Title of " + ROOT_LOGIN))
                .andExpect(jsonPath("$.results[0].evidence").value(nullValue()))
                .andExpect(hasPropertyNames(
                        "$",
                        "id",
                        "hostId",
                        "agentVersion",
                        "startedAt",
                        "finishedAt",
                        "receivedAt",
                        "score",
                        "counts",
                        "hostname",
                        "results"))
                .andExpect(hasPropertyNames("$.counts", "pass", "fail", "error", "notApplicable"))
                .andExpect(hasPropertyNames(
                        "$.results[0]",
                        "checkId",
                        "title",
                        "category",
                        "severity",
                        "status",
                        "expected",
                        "actual",
                        "evidence"));
        getJson("/api/v1/scans/{id}", UUID.randomUUID()).andExpect(status().isNotFound());
        getJson("/api/v1/scans/12345").andExpect(status().isNotFound());
    }
}
