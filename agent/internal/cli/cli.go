// Package cli implements the driftwatch-agent command line.
package cli

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	"github.com/TahaUsama210/driftwatch/agent/internal/checks"
	"github.com/TahaUsama210/driftwatch/agent/internal/client"
	"github.com/TahaUsama210/driftwatch/agent/internal/facts"
	"github.com/TahaUsama210/driftwatch/agent/internal/hostfs"
	"github.com/TahaUsama210/driftwatch/agent/internal/runner"
	"github.com/TahaUsama210/driftwatch/agent/internal/scan"
	"github.com/TahaUsama210/driftwatch/agent/internal/secret"
	"github.com/TahaUsama210/driftwatch/agent/internal/state"
)

// Exit codes.
const (
	ExitOK    = 0
	ExitError = 1
	ExitUsage = 2
)

// minInterval keeps a misconfigured agent from flooding the API.
const minInterval = 10 * time.Second

// BuildInfo identifies the binary; it is injected at link time.
type BuildInfo struct {
	Version string
	Commit  string
}

// Env looks up an environment variable, like os.Getenv.
type Env func(key string) string

// Main runs the command line args (without the program name) and returns the
// process exit code. ctx is canceled when the process is asked to stop.
func Main(ctx context.Context, args []string, env Env, stdout, stderr io.Writer, build BuildInfo) int {
	if len(args) == 0 {
		report(stderr, usageText)
		return ExitUsage
	}

	var err error
	switch cmd, rest := args[0], args[1:]; cmd {
	case "run":
		err = runCmd(ctx, rest, env, stderr, build)
	case "scan":
		err = scanCmd(rest, env, stdout, stderr, build)
	case "checks":
		err = checksCmd(rest, stdout, stderr)
	case "version":
		err = versionCmd(rest, stdout, stderr, build)
	case "help", "-h", "-help", "--help":
		report(stdout, usageText)
		return ExitOK
	default:
		err = usageErrorf("unknown command %q", cmd)
	}

	if _, ok := errors.AsType[*usageError](err); ok {
		report(stderr, fmt.Sprintf("driftwatch-agent: %v\nRun 'driftwatch-agent help' for usage.\n", err))
		return ExitUsage
	}
	if err != nil && !errors.Is(err, flag.ErrHelp) {
		report(stderr, fmt.Sprintf("driftwatch-agent: %v\n", err))
		return ExitError
	}
	return ExitOK
}

// report writes a final message for the user. If even that fails there is no
// channel left to report the failure on, so the write error is dropped.
func report(w io.Writer, msg string) {
	_, _ = io.WriteString(w, msg)
}

const usageText = `driftwatch-agent scans a Linux host against the Driftwatch hardening checks.

Usage:
  driftwatch-agent run [flags]       enroll if needed, then scan and report every interval
  driftwatch-agent scan [flags]      run one local scan and print the results (no network)
  driftwatch-agent checks            print the check catalog
  driftwatch-agent version           print version information

Run 'driftwatch-agent <command> -h' for the flags of a command.
`

type usageError struct{ msg string }

func (e *usageError) Error() string { return e.msg }

func usageErrorf(format string, args ...any) error {
	return &usageError{msg: fmt.Sprintf(format, args...)}
}

// newFlagSet returns a flag set whose parse errors are reported once, by Main.
func newFlagSet(name string, stderr io.Writer) *flag.FlagSet {
	fs := flag.NewFlagSet(name, flag.ContinueOnError)
	fs.SetOutput(stderr)
	return fs
}

func parse(fs *flag.FlagSet, args []string) error {
	if err := fs.Parse(args); err != nil {
		if errors.Is(err, flag.ErrHelp) {
			return err
		}
		return &usageError{msg: err.Error()}
	}
	if fs.NArg() > 0 {
		return usageErrorf("%s: unexpected arguments: %s", fs.Name(), strings.Join(fs.Args(), " "))
	}
	return nil
}

// targetFlags are the flags that choose what is scanned.
type targetFlags struct {
	root string
	proc string
}

func (t *targetFlags) register(fs *flag.FlagSet, env Env) {
	fs.StringVar(&t.root, "root", envOr(env, "DRIFTWATCH_ROOT", "/"),
		"filesystem `root` to scan, e.g. a host root mounted read-only (env DRIFTWATCH_ROOT)")
	fs.StringVar(&t.proc, "proc", envOr(env, "DRIFTWATCH_PROC", "/proc"),
		"procfs `dir` to read kernel parameters from (env DRIFTWATCH_PROC)")
}

func (t *targetFlags) scanner(build BuildInfo) *scan.Scanner {
	root, proc := hostfs.Dir(t.root), hostfs.Dir(t.proc)
	collector := facts.Collector{Root: root, Proc: proc}
	if filepath.Clean(t.root) == string(filepath.Separator) {
		// Scanning the system we run on: the kernel's hostname is authoritative.
		collector.Hostname = os.Hostname
	}
	return &scan.Scanner{
		Root:         root,
		Proc:         proc,
		Facts:        collector,
		Catalog:      checks.Catalog(),
		AgentVersion: build.Version,
	}
}

func runCmd(ctx context.Context, args []string, env Env, stderr io.Writer, build BuildInfo) error {
	interval, err := envDuration(env, "DRIFTWATCH_INTERVAL", 5*time.Minute)
	if err != nil {
		return err
	}
	logger, err := newLogger(env("DRIFTWATCH_LOG_FORMAT"), stderr)
	if err != nil {
		return err
	}

	var (
		target          targetFlags
		server          string
		enrollmentToken string
		stateDir        string
	)
	fs := newFlagSet("run", stderr)
	target.register(fs, env)
	fs.StringVar(&server, "server", env("DRIFTWATCH_SERVER_URL"),
		"API base `url`, e.g. http://api:8080 (env DRIFTWATCH_SERVER_URL)")
	// The environment value is applied after parsing rather than as the flag's
	// default, because usage output prints defaults.
	fs.StringVar(&enrollmentToken, "enrollment-token", "",
		"shared `token` used for the first enrollment only (env DRIFTWATCH_ENROLLMENT_TOKEN; prefer the\n"+
			"environment variable, since command-line arguments are visible to other local users)")
	fs.DurationVar(&interval, "interval", interval,
		"time between scans, at least 10s (env DRIFTWATCH_INTERVAL)")
	fs.StringVar(&stateDir, "state-dir", envOr(env, "DRIFTWATCH_STATE_DIR", "/var/lib/driftwatch"),
		"`dir` for state.json, which holds the host ID and agent token (env DRIFTWATCH_STATE_DIR)")
	if err := parse(fs, args); err != nil {
		return err
	}
	if enrollmentToken == "" {
		enrollmentToken = env("DRIFTWATCH_ENROLLMENT_TOKEN")
	}

	if server == "" {
		return usageErrorf("run: --server (or DRIFTWATCH_SERVER_URL) is required")
	}
	if interval < minInterval {
		return usageErrorf("run: --interval must be at least %s, got %s", minInterval, interval)
	}
	userAgent := fmt.Sprintf("driftwatch-agent/%s (%s/%s)", build.Version, runtime.GOOS, runtime.GOARCH)
	api, err := client.New(client.Config{BaseURL: server, UserAgent: userAgent, Logger: logger})
	if err != nil {
		return usageErrorf("run: %v", err)
	}

	r := &runner.Runner{
		Client:          api,
		Store:           state.NewStore(stateDir),
		Scanner:         target.scanner(build),
		Logger:          logger,
		Interval:        interval,
		EnrollmentToken: secret.Token(enrollmentToken),
	}
	if err := r.Run(ctx); err != nil {
		logger.ErrorContext(ctx, "agent stopped", slog.Any("error", err))
		return err
	}
	return nil
}

func scanCmd(args []string, env Env, stdout, stderr io.Writer, build BuildInfo) error {
	var (
		target targetFlags
		format string
	)
	fs := newFlagSet("scan", stderr)
	target.register(fs, env)
	fs.StringVar(&format, "format", "table", "output `format`: table or json")
	if err := parse(fs, args); err != nil {
		return err
	}
	if format != "table" && format != "json" {
		return usageErrorf("scan: --format must be table or json, got %q", format)
	}

	report, err := target.scanner(build).Scan()
	if err != nil {
		return err
	}
	if format == "json" {
		return writeJSON(stdout, report)
	}
	return writeScanTable(stdout, &report)
}

func checksCmd(args []string, stdout, stderr io.Writer) error {
	if err := parse(newFlagSet("checks", stderr), args); err != nil {
		return err
	}
	return writeCatalog(stdout, checks.Catalog())
}

func versionCmd(args []string, stdout, stderr io.Writer, build BuildInfo) error {
	if err := parse(newFlagSet("version", stderr), args); err != nil {
		return err
	}
	_, err := fmt.Fprintf(stdout, "driftwatch-agent %s (commit %s, %s %s/%s)\n",
		build.Version, build.Commit, runtime.Version(), runtime.GOOS, runtime.GOARCH)
	return err
}

func newLogger(format string, w io.Writer) (*slog.Logger, error) {
	switch format {
	case "", "json":
		return slog.New(slog.NewJSONHandler(w, nil)), nil
	case "text":
		return slog.New(slog.NewTextHandler(w, nil)), nil
	default:
		return nil, usageErrorf("DRIFTWATCH_LOG_FORMAT must be json or text, got %q", format)
	}
}

func envOr(env Env, key, fallback string) string {
	if v := env(key); v != "" {
		return v
	}
	return fallback
}

func envDuration(env Env, key string, fallback time.Duration) (time.Duration, error) {
	v := env(key)
	if v == "" {
		return fallback, nil
	}
	d, err := time.ParseDuration(v)
	if err != nil {
		return 0, usageErrorf("%s: %v", key, err)
	}
	return d, nil
}
