package cli_test

import (
	"bytes"
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/TahaUsama210/driftwatch/agent/internal/checks"
	"github.com/TahaUsama210/driftwatch/agent/internal/cli"
	"github.com/TahaUsama210/driftwatch/agent/internal/scan"
)

var build = cli.BuildInfo{Version: "1.2.3", Commit: "abc1234"}

// env returns an Env backed by vars.
func env(vars map[string]string) cli.Env {
	return func(key string) string { return vars[key] }
}

func run(t *testing.T, vars map[string]string, args ...string) (code int, stdout, stderr string) {
	t.Helper()
	var out, errOut bytes.Buffer
	code = cli.Main(t.Context(), args, env(vars), &out, &errOut, build)
	return code, out.String(), errOut.String()
}

// fakeHost writes a minimal host root and procfs and returns their paths.
func fakeHost(t *testing.T) (root, proc string) {
	t.Helper()
	dir := t.TempDir()
	root, proc = filepath.Join(dir, "root"), filepath.Join(dir, "proc")
	for name, content := range map[string]string{
		filepath.Join(root, "etc", "hostname"):                  "web-1\n",
		filepath.Join(root, "etc", "os-release"):                "ID=debian\nVERSION_ID=12\nPRETTY_NAME=\"Debian GNU/Linux 12 (bookworm)\"\n",
		filepath.Join(root, "etc", "ssh", "sshd_config"):        "PermitRootLogin no\n",
		filepath.Join(proc, "sys", "kernel", "osrelease"):       "6.8.0\n",
		filepath.Join(proc, "sys", "net", "ipv4", "ip_forward"): "0\n",
	} {
		if err := os.MkdirAll(filepath.Dir(name), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(name, []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	return root, proc
}

func TestVersion(t *testing.T) {
	code, stdout, _ := run(t, nil, "version")
	want := "driftwatch-agent 1.2.3 (commit abc1234, " + runtime.Version() + " " + runtime.GOOS + "/" + runtime.GOARCH + ")\n"
	if code != cli.ExitOK || stdout != want {
		t.Errorf("version = %d %q, want 0 %q", code, stdout, want)
	}
}

func TestChecksPrintsTheCatalog(t *testing.T) {
	code, stdout, _ := run(t, nil, "checks")
	if code != cli.ExitOK {
		t.Fatalf("exit code %d", code)
	}
	lines := strings.Split(strings.TrimSpace(stdout), "\n")
	if len(lines) != 1+len(checks.Catalog()) || !strings.HasPrefix(lines[0], "ID") {
		t.Fatalf("checks output has %d lines:\n%s", len(lines), stdout)
	}
	if !strings.Contains(stdout, "accounts.uid0_only_root") || !strings.Contains(stdout, "Only root has UID 0") {
		t.Errorf("catalog output lacks a known check:\n%s", stdout)
	}
}

func TestScanJSONPrintsTheReport(t *testing.T) {
	root, proc := fakeHost(t)

	code, stdout, stderr := run(t, map[string]string{"DRIFTWATCH_PROC": proc}, "scan", "--format", "json", "--root", root)
	if code != cli.ExitOK {
		t.Fatalf("exit code %d: %s", code, stderr)
	}

	var report scan.Report
	if err := json.Unmarshal([]byte(stdout), &report); err != nil {
		t.Fatalf("output is not a scan report: %v\n%s", err, stdout)
	}
	if report.Host.Hostname != "web-1" || report.Host.OS.ID != "debian" || report.AgentVersion != "1.2.3" {
		t.Errorf("report = %+v", report)
	}
	byID := map[string]checks.Result{}
	for _, r := range report.Results {
		byID[r.CheckID] = r
	}
	if len(byID) != len(checks.Catalog()) {
		t.Errorf("report has %d distinct results, want %d", len(byID), len(checks.Catalog()))
	}
	if r := byID["ssh.permit_root_login"]; r.Status != checks.StatusPass {
		t.Errorf("ssh.permit_root_login = %+v, want PASS from the fake host", r)
	}
	if r := byID["network.ip_forwarding_disabled"]; r.Status != checks.StatusPass {
		t.Errorf("network.ip_forwarding_disabled = %+v, want PASS from the fake procfs", r)
	}
}

func TestScanTablePrintsResultsAndScore(t *testing.T) {
	root, proc := fakeHost(t)

	code, stdout, stderr := run(t, map[string]string{"DRIFTWATCH_ROOT": root, "DRIFTWATCH_PROC": proc}, "scan")
	if code != cli.ExitOK {
		t.Fatalf("exit code %d: %s", code, stderr)
	}
	for _, want := range []string{"Host: web-1 (Debian GNU/Linux 12 (bookworm), kernel 6.8.0,", "CHECK", "ssh.permit_root_login", "Score: "} {
		if !strings.Contains(stdout, want) {
			t.Errorf("table output lacks %q:\n%s", want, stdout)
		}
	}
}

func TestScanFailsWhenFactsAreMissing(t *testing.T) {
	code, _, stderr := run(t, nil, "scan", "--root", t.TempDir(), "--proc", t.TempDir())
	if code != cli.ExitError || !strings.Contains(stderr, "collect host facts") {
		t.Errorf("scan of an empty root = %d %q, want exit 1 with a facts error", code, stderr)
	}
}

func TestUsageErrors(t *testing.T) {
	tests := []struct {
		name    string
		vars    map[string]string
		args    []string
		wantErr string
	}{
		{"no command", nil, nil, "Usage:"},
		{"unknown command", nil, []string{"deploy"}, `unknown command "deploy"`},
		{"unknown flag", nil, []string{"scan", "--verbose"}, "flag provided but not defined"},
		{"positional arguments", nil, []string{"checks", "extra"}, "unexpected arguments: extra"},
		{"bad scan format", nil, []string{"scan", "--format", "yaml"}, "--format must be table or json"},
		{"run without server", nil, []string{"run"}, "--server (or DRIFTWATCH_SERVER_URL) is required"},
		{"run with invalid server", nil, []string{"run", "--server", "api:8080"}, "invalid server URL"},
		{"interval below minimum", nil, []string{"run", "--server", "http://api:8080", "--interval", "1s"}, "--interval must be at least 10s"},
		{"invalid interval env", map[string]string{"DRIFTWATCH_INTERVAL": "often"}, []string{"run"}, "DRIFTWATCH_INTERVAL"},
		{"invalid log format", map[string]string{"DRIFTWATCH_LOG_FORMAT": "xml"}, []string{"run"}, "DRIFTWATCH_LOG_FORMAT must be json or text"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			code, _, stderr := run(t, tt.vars, tt.args...)
			if code != cli.ExitUsage || !strings.Contains(stderr, tt.wantErr) {
				t.Errorf("exit %d, stderr %q; want %d containing %q", code, stderr, cli.ExitUsage, tt.wantErr)
			}
		})
	}
}

func TestUsageOutputNeverShowsTheEnrollmentToken(t *testing.T) {
	const token = "enroll-secret-0123456789abcdef"
	vars := map[string]string{"DRIFTWATCH_ENROLLMENT_TOKEN": token}
	for _, args := range [][]string{{"run", "-h"}, {"run", "--no-such-flag"}} {
		_, stdout, stderr := run(t, vars, args...)
		if strings.Contains(stdout+stderr, token) {
			t.Errorf("%v: output contains the enrollment token:\n%s", args, stdout+stderr)
		}
	}
}

func TestHelpExitsSuccessfully(t *testing.T) {
	for _, args := range [][]string{{"help"}, {"--help"}, {"scan", "-h"}, {"run", "-h"}} {
		code, stdout, stderr := run(t, nil, args...)
		if code != cli.ExitOK || stdout+stderr == "" {
			t.Errorf("%v: exit %d with no output", args, code)
		}
	}
}

// TestRunEnrollsAndSubmits wires the real command against a fake API and
// stops it once the first scan has been accepted.
func TestRunEnrollsAndSubmits(t *testing.T) {
	root, proc := fakeHost(t)
	submitted := make(chan struct{})
	var once sync.Once
	api := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if _, err := io.Copy(io.Discard, r.Body); err != nil {
			t.Errorf("read body: %v", err)
		}
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/api/v1/agents/enroll":
			w.WriteHeader(http.StatusCreated)
			_, _ = io.WriteString(w, `{"hostId":"9b2f8a1e-8f0a-4c9e-9d38-0d4c8b5f2a11","agentToken":"dwa_cli-test"}`)
		case "/api/v1/scans":
			w.WriteHeader(http.StatusCreated)
			_, _ = io.WriteString(w, `{"id":"9b2f8a1e-8f0a-4c9e-9d38-0d4c8b5f2a12","score":50,"counts":{"pass":1,"fail":1,"error":0,"notApplicable":0}}`)
			once.Do(func() { close(submitted) })
		default:
			w.WriteHeader(http.StatusNotFound)
		}
	}))
	defer api.Close()

	ctx, cancel := context.WithCancel(t.Context())
	var stderr bytes.Buffer
	done := make(chan int, 1)
	vars := map[string]string{
		"DRIFTWATCH_SERVER_URL":       api.URL,
		"DRIFTWATCH_ENROLLMENT_TOKEN": "enroll-secret-0123456789abcdef",
		"DRIFTWATCH_STATE_DIR":        filepath.Join(t.TempDir(), "state"),
		"DRIFTWATCH_INTERVAL":         "30s",
		"DRIFTWATCH_LOG_FORMAT":       "text",
	}
	go func() {
		done <- cli.Main(ctx, []string{"run", "--root", root, "--proc", proc}, env(vars), io.Discard, &stderr, build)
	}()

	select {
	case <-submitted:
	case <-time.After(10 * time.Second):
		t.Fatal("no scan was submitted")
	}
	cancel()
	if code := <-done; code != cli.ExitOK {
		t.Errorf("run exited %d: %s", code, stderr.String())
	}
	if _, err := os.Stat(filepath.Join(vars["DRIFTWATCH_STATE_DIR"], "state.json")); err != nil {
		t.Errorf("state was not saved: %v", err)
	}
}

func TestRunExitsWithAnErrorWhenEnrollmentIsRejected(t *testing.T) {
	root, proc := fakeHost(t)
	api := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusUnauthorized)
	}))
	defer api.Close()

	vars := map[string]string{
		"DRIFTWATCH_ENROLLMENT_TOKEN": "wrong",
		"DRIFTWATCH_STATE_DIR":        t.TempDir(),
	}
	code, _, stderr := run(t, vars, "run", "--server", api.URL, "--root", root, "--proc", proc)
	if code != cli.ExitError || !strings.Contains(stderr, "rejected enrollment") {
		t.Errorf("exit %d, stderr %q; want 1 with a rejection", code, stderr)
	}
}
