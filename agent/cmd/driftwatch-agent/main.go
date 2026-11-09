// Command driftwatch-agent scans a Linux host against the Driftwatch hardening
// checks and reports the results to the Driftwatch API.
package main

import (
	"context"
	"os"
	"os/signal"
	"syscall"

	"github.com/TahaUsama210/driftwatch/agent/internal/cli"
)

// Set at link time: -ldflags "-X main.version=... -X main.commit=...".
var (
	version = "dev"
	commit  = "unknown"
)

func main() {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	code := cli.Main(ctx, os.Args[1:], os.Getenv, os.Stdout, os.Stderr, cli.BuildInfo{Version: version, Commit: commit})
	stop()
	os.Exit(code)
}
