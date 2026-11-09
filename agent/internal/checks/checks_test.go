package checks_test

import (
	"io/fs"
	"regexp"
	"strings"
	"testing"
	"unicode/utf8"

	"github.com/TahaUsama210/driftwatch/agent/internal/checks"
	"github.com/TahaUsama210/driftwatch/agent/internal/hostfs"
	"github.com/TahaUsama210/driftwatch/agent/internal/hostfs/hostfstest"
)

// text returns a root-owned file with the given contents and mode.
func text(content string, mode fs.FileMode) hostfstest.File {
	return hostfstest.File{Data: []byte(content), Mode: mode}
}

// runCheck evaluates the catalog check id against the given filesystems.
func runCheck(t *testing.T, id string, root, proc hostfstest.MapFS) checks.Result {
	t.Helper()
	for _, c := range checks.Catalog() {
		if c.ID == id {
			return checks.Run(checks.NewHost(root, proc), []checks.Check{c})[0]
		}
	}
	t.Fatalf("no check %q in the catalog", id)
	return checks.Result{}
}

// want describes the expected parts of a result. Empty fields are not
// checked; evidence is matched as a substring.
type want struct {
	status   checks.Status
	actual   string
	expected string
	evidence string
}

func (w want) verify(t *testing.T, got checks.Result) {
	t.Helper()
	if got.Status != w.status {
		t.Errorf("status = %s, want %s (evidence: %s)", got.Status, w.status, got.Evidence)
	}
	if w.actual != "" && got.Actual != w.actual {
		t.Errorf("actual = %q, want %q", got.Actual, w.actual)
	}
	if w.expected != "" && got.Expected != w.expected {
		t.Errorf("expected = %q, want %q", got.Expected, w.expected)
	}
	if !strings.Contains(got.Evidence, w.evidence) {
		t.Errorf("evidence = %q, want it to contain %q", got.Evidence, w.evidence)
	}
}

// TestCatalogMatchesContract pins the catalog to the table in
// docs/CONTRACTS.md; the API and the dashboard depend on these exact values.
func TestCatalogMatchesContract(t *testing.T) {
	contract := []struct {
		id, title string
		category  checks.Category
		severity  checks.Severity
	}{
		{"ssh.permit_root_login", "SSH root login is disabled", "ssh", "high"},
		{"ssh.password_authentication", "SSH password authentication is disabled", "ssh", "high"},
		{"ssh.permit_empty_passwords", "SSH rejects empty passwords", "ssh", "critical"},
		{"ssh.max_auth_tries", "SSH limits authentication attempts", "ssh", "medium"},
		{"ssh.x11_forwarding", "SSH X11 forwarding is disabled", "ssh", "low"},
		{"ssh.login_grace_time", "SSH login grace time is 60s or less", "ssh", "low"},
		{"ssh.idle_timeout", "SSH idle sessions time out", "ssh", "low"},
		{"ssh.config_permissions", "sshd_config is owned by root and not group/world accessible", "ssh", "medium"},
		{"accounts.shadow_permissions", "/etc/shadow is protected", "accounts", "high"},
		{"accounts.passwd_permissions", "/etc/passwd is not writable by group or others", "accounts", "medium"},
		{"accounts.uid0_only_root", "Only root has UID 0", "accounts", "critical"},
		{"accounts.no_empty_passwords", "No account has an empty password", "accounts", "critical"},
		{"accounts.pass_max_days", "Passwords expire within 365 days", "accounts", "medium"},
		{"accounts.pass_min_days", "Passwords have a minimum age", "accounts", "low"},
		{"accounts.pass_warn_age", "Users are warned before password expiry", "accounts", "low"},
		{"network.ip_forwarding_disabled", "IPv4 forwarding is disabled", "network", "medium"},
		{"network.icmp_redirects_rejected", "ICMP redirects are not accepted", "network", "medium"},
		{"network.source_routing_rejected", "Source-routed packets are not accepted", "network", "medium"},
		{"network.syn_cookies_enabled", "TCP SYN cookies are enabled", "network", "medium"},
		{"kernel.aslr_enabled", "Full address space layout randomization is enabled", "kernel", "high"},
		{"filesystem.crontab_permissions", "/etc/crontab is owned by root and private", "filesystem", "low"},
	}

	catalog := checks.Catalog()
	if len(catalog) != len(contract) {
		t.Fatalf("catalog has %d checks, contract has %d", len(catalog), len(contract))
	}
	idPattern := regexp.MustCompile(`^[a-z0-9_.]{3,64}$`)
	for i, c := range catalog {
		w := contract[i]
		if c.ID != w.id || c.Title != w.title || c.Category != w.category || c.Severity != w.severity {
			t.Errorf("check %d = {%s %q %s %s}, contract says {%s %q %s %s}",
				i, c.ID, c.Title, c.Category, c.Severity, w.id, w.title, w.category, w.severity)
		}
		if !idPattern.MatchString(c.ID) {
			t.Errorf("check ID %q does not match %s", c.ID, idPattern)
		}
		if c.Evaluate == nil {
			t.Errorf("check %s has no Evaluate function", c.ID)
		}
	}
}

func TestSeverityWeights(t *testing.T) {
	tests := map[checks.Severity]int{
		checks.SeverityLow:      1,
		checks.SeverityMedium:   2,
		checks.SeverityHigh:     3,
		checks.SeverityCritical: 5,
		"unknown":               0,
	}
	for severity, weight := range tests {
		if got := severity.Weight(); got != weight {
			t.Errorf("%s.Weight() = %d, want %d", severity, got, weight)
		}
	}
}

func TestRunTurnsAPanickingCheckIntoAnError(t *testing.T) {
	boom := checks.Check{ID: "test.boom", Evaluate: func(*checks.Host) checks.Outcome { panic("nil map") }}
	fine := checks.Check{ID: "test.fine", Evaluate: func(*checks.Host) checks.Outcome {
		return checks.Outcome{Status: checks.StatusPass}
	}}

	results := checks.Run(checks.NewHost(hostfstest.MapFS{}, hostfstest.MapFS{}), []checks.Check{boom, fine})

	if results[0].Status != checks.StatusError || !strings.Contains(results[0].Evidence, "internal error in check test.boom: nil map") {
		t.Errorf("panicking check = %+v, want ERROR with the panic in evidence", results[0])
	}
	if results[1].Status != checks.StatusPass {
		t.Errorf("the check after a panic did not run: %+v", results[1])
	}
}

func TestRunTruncatesFieldsToAPILimits(t *testing.T) {
	long := strings.Repeat("é", 600) // 1200 bytes of two-byte runes
	c := checks.Check{ID: "test.long", Evaluate: func(*checks.Host) checks.Outcome {
		return checks.Outcome{Status: checks.StatusFail, Expected: long, Actual: long, Evidence: long}
	}}

	got := checks.Run(checks.NewHost(hostfstest.MapFS{}, hostfstest.MapFS{}), []checks.Check{c})[0]

	for field, value := range map[string]struct {
		s     string
		limit int
	}{"expected": {got.Expected, 200}, "actual": {got.Actual, 200}, "evidence": {got.Evidence, 1000}} {
		if len(value.s) > value.limit || !utf8.ValidString(value.s) || !strings.HasSuffix(value.s, "...") {
			t.Errorf("%s has %d bytes (limit %d), valid UTF-8 %v: %q...", field, len(value.s), value.limit, utf8.ValidString(value.s), value.s[:20])
		}
	}
}

// countingFS counts reads per file.
type countingFS struct {
	hostfs.FS
	reads map[string]int
}

func (c *countingFS) ReadFile(name string) ([]byte, error) {
	c.reads[name]++
	return c.FS.ReadFile(name)
}

func TestHostParsesEachFileOncePerScan(t *testing.T) {
	root := &countingFS{FS: hostfstest.MapFS{
		"/etc/ssh/sshd_config": text("PermitRootLogin no\n", 0o600),
		"/etc/passwd":          text("root:x:0:0:root:/root:/bin/sh\n", 0o644),
		"/etc/shadow":          text("root:*:19000:0:99999:7:::\n", 0o640),
		"/etc/login.defs":      text("PASS_MAX_DAYS 90\n", 0o644),
	}, reads: map[string]int{}}

	checks.Run(checks.NewHost(root, hostfstest.MapFS{}), checks.Catalog())

	for name, n := range root.reads {
		if n != 1 {
			t.Errorf("%s read %d times, want 1", name, n)
		}
	}
}
