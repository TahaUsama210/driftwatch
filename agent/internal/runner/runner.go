// Package runner implements the agent's long-running mode: enroll once, then
// scan and report on a fixed interval until shut down.
package runner

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"time"

	"github.com/TahaUsama210/driftwatch/agent/internal/client"
	"github.com/TahaUsama210/driftwatch/agent/internal/facts"
	"github.com/TahaUsama210/driftwatch/agent/internal/scan"
	"github.com/TahaUsama210/driftwatch/agent/internal/secret"
	"github.com/TahaUsama210/driftwatch/agent/internal/state"
)

// Runner is the agent's main loop. Fields must be set before Run is called.
type Runner struct {
	Client  *client.Client
	Store   *state.Store
	Scanner *scan.Scanner
	Logger  *slog.Logger

	// Interval is the time between scans.
	Interval time.Duration
	// EnrollmentToken is presented when the agent has no valid agent token.
	EnrollmentToken secret.Token

	state     state.State
	machineID string
}

// Run enrolls if necessary, then scans and submits immediately and every
// Interval until ctx is canceled, at which point it returns nil. Cancellation
// stops all retrying at once; the Client lets a request already in flight
// finish within its shutdown grace period.
//
// Failures that may resolve themselves (the API being unreachable, a rejected
// scan) are logged and the agent tries again at the next interval. Run
// returns an error only when continuing cannot help: the state file cannot be
// read or written, or the API rejects the enrollment request.
func (r *Runner) Run(ctx context.Context) error {
	if r.Interval <= 0 {
		return fmt.Errorf("interval must be positive, got %s", r.Interval)
	}
	if err := r.loadIdentity(); err != nil {
		return err
	}
	r.Logger.InfoContext(ctx, "agent started",
		slog.String("version", r.Scanner.AgentVersion),
		slog.Duration("interval", r.Interval),
		slog.Bool("enrolled", r.state.Enrolled()))

	ticker := time.NewTicker(r.Interval)
	defer ticker.Stop()
	for ctx.Err() == nil {
		if err := r.cycle(ctx); err != nil {
			return err
		}
		select {
		case <-ctx.Done():
		case <-ticker.C:
		}
	}
	r.Logger.InfoContext(ctx, "agent stopped")
	return nil
}

// loadIdentity loads saved state and settles the machine ID, persisting a
// newly generated one before it is ever sent to the API.
func (r *Runner) loadIdentity() error {
	st, err := r.Store.Load()
	if err != nil {
		return err
	}
	id, fresh, err := facts.MachineID(r.Scanner.Root, st.MachineID)
	if err != nil {
		return fmt.Errorf("read machine ID: %w", err)
	}
	if fresh {
		st.MachineID = id
		if err := r.Store.Save(st); err != nil {
			return fmt.Errorf("persist generated machine ID: %w", err)
		}
		r.Logger.Info("no usable /etc/machine-id; generated a persistent machine ID",
			slog.String("machineId", id), slog.String("stateFile", r.Store.Path()))
	}
	r.state, r.machineID = st, id
	return nil
}

// cycle runs one enroll-if-needed, scan and submit round. It returns an error
// only when the agent must stop.
func (r *Runner) cycle(ctx context.Context) error {
	if !r.state.Enrolled() {
		if err := r.enroll(ctx); err != nil {
			return r.recoverable(ctx, "enrollment failed; will retry at next interval", err)
		}
	}

	report, err := r.Scanner.Scan()
	if err != nil {
		r.Logger.ErrorContext(ctx, "scan failed; will retry at next interval", slog.Any("error", err))
		return nil
	}
	log := r.Logger.With(slog.String("scanId", report.ScanID.String()))

	summary, err := r.Client.SubmitScan(ctx, r.state.AgentToken, report)
	if errors.Is(err, client.ErrUnauthorized) {
		// The token was rotated or the host was deleted server-side. Enroll
		// again once and resubmit the same report (same scan ID).
		log.WarnContext(ctx, "agent token rejected; re-enrolling", slog.String("hostId", r.state.HostID.String()))
		r.state.AgentToken = ""
		if err := r.enroll(ctx); err != nil {
			return r.recoverable(ctx, "re-enrollment failed; will retry at next interval", err)
		}
		summary, err = r.Client.SubmitScan(ctx, r.state.AgentToken, report)
	}
	if err != nil {
		level, msg := slog.LevelError, "scan submission failed; a new scan will be submitted at the next interval"
		if ctx.Err() != nil {
			// Shutting down is expected, not a failure.
			level, msg = slog.LevelWarn, "scan submission interrupted by shutdown"
		}
		log.Log(ctx, level, msg, slog.Any("error", err))
		return nil
	}

	attrs := []any{
		slog.String("hostId", r.state.HostID.String()),
		slog.Bool("duplicate", summary.Duplicate),
		slog.Int("pass", summary.Counts.Pass),
		slog.Int("fail", summary.Counts.Fail),
		slog.Int("error", summary.Counts.Error),
		slog.Int("notApplicable", summary.Counts.NotApplicable),
	}
	if summary.Score != nil {
		attrs = append(attrs, slog.Float64("score", *summary.Score))
	}
	log.InfoContext(ctx, "scan submitted", attrs...)
	return nil
}

// enroll obtains a host ID and agent token and persists them. Errors that
// retrying cannot fix are returned as *fatalError.
func (r *Runner) enroll(ctx context.Context) error {
	if r.EnrollmentToken == "" {
		return &fatalError{err: errors.New("the agent must enroll but no enrollment token is configured")}
	}
	host, err := r.Scanner.Facts.Collect()
	if err != nil {
		return fmt.Errorf("collect host facts: %w", err)
	}
	e, err := r.Client.Enroll(ctx, r.EnrollmentToken, client.EnrollRequest{
		Host:         host,
		MachineID:    r.machineID,
		AgentVersion: r.Scanner.AgentVersion,
	})
	if se, ok := errors.AsType[*client.StatusError](err); ok && se.StatusCode < http.StatusInternalServerError {
		// The enrollment token or the request is wrong; asking again will not
		// change the answer.
		return &fatalError{err: fmt.Errorf("the API rejected enrollment: %w", err)}
	}
	if err != nil {
		return err
	}

	r.state.HostID, r.state.AgentToken = e.HostID, e.AgentToken
	if err := r.Store.Save(r.state); err != nil {
		return &fatalError{err: fmt.Errorf("persist enrollment: %w", err)}
	}
	r.Logger.InfoContext(ctx, "enrolled", slog.String("hostId", e.HostID.String()), slog.String("hostname", host.Hostname))
	return nil
}

// recoverable returns err if the agent must stop; otherwise it logs err and
// returns nil so the agent tries again at the next interval.
func (r *Runner) recoverable(ctx context.Context, msg string, err error) error {
	if _, ok := errors.AsType[*fatalError](err); ok {
		return err
	}
	level := slog.LevelError
	if ctx.Err() != nil {
		// Shutting down is expected, not a failure.
		level, msg = slog.LevelWarn, "enrollment interrupted by shutdown"
	}
	r.Logger.Log(ctx, level, msg, slog.Any("error", err))
	return nil
}

// fatalError marks a failure that retrying cannot fix.
type fatalError struct{ err error }

func (e *fatalError) Error() string { return e.err.Error() }
func (e *fatalError) Unwrap() error { return e.err }
