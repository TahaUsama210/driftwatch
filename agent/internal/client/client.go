// Package client calls the Driftwatch API on behalf of the agent.
//
// Requests are retried with exponential backoff and full jitter when the
// failure is plausibly transient (a transport error or a 5xx response). A 4xx
// response is returned immediately: repeating the same request cannot fix it.
// Retrying a scan submission is safe because the API deduplicates on scanId.
//
// Canceling the context passed to a method stops it from starting any further
// attempt or backoff wait. A request that is already in flight is allowed to
// finish within a short grace period, so an enrollment or scan the server has
// accepted is not thrown away at shutdown.
package client

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"math/rand/v2"
	"net/http"
	"net/url"
	"time"
	"uuid"

	"github.com/TahaUsama210/driftwatch/agent/internal/facts"
	"github.com/TahaUsama210/driftwatch/agent/internal/scan"
	"github.com/TahaUsama210/driftwatch/agent/internal/score"
	"github.com/TahaUsama210/driftwatch/agent/internal/secret"
)

const (
	enrollPath = "api/v1/agents/enroll"
	scansPath  = "api/v1/scans"

	// maxResponseSize bounds how much of a response body is read.
	maxResponseSize = 1 << 20

	defaultTimeout = 30 * time.Second
)

// DefaultShutdownGrace is how long an in-flight request may continue after its
// context is canceled.
const DefaultShutdownGrace = 5 * time.Second

// ErrUnauthorized matches (with errors.Is) a 401 response: the presented
// token is missing, wrong or has been rotated.
var ErrUnauthorized = errors.New("unauthorized")

// RetryPolicy controls how failed requests are retried.
type RetryPolicy struct {
	Attempts  int           // total attempts, including the first
	BaseDelay time.Duration // backoff ceiling before the first retry
	MaxDelay  time.Duration // cap on the backoff ceiling
}

// DefaultRetry is the policy from docs/CONTRACTS.md: 1s base, 60s cap, give up
// after 5 attempts.
var DefaultRetry = RetryPolicy{Attempts: 5, BaseDelay: time.Second, MaxDelay: time.Minute}

// Config configures a Client.
type Config struct {
	// BaseURL is the API's base URL, e.g. http://api:8080.
	BaseURL string
	// UserAgent is sent with every request.
	UserAgent string
	// HTTPClient performs requests. Nil means a client with a 30s timeout per
	// attempt.
	HTTPClient *http.Client
	// Logger receives retry warnings and per-request debug records. Nil means
	// slog.Default().
	Logger *slog.Logger
	// Retry is the retry policy. The zero value means DefaultRetry.
	Retry RetryPolicy
	// ShutdownGrace bounds how long a request already in flight may continue
	// after the caller's context is canceled. Zero means DefaultShutdownGrace.
	ShutdownGrace time.Duration
}

// Client is a Driftwatch API client. It is safe for concurrent use.
type Client struct {
	base      *url.URL
	http      *http.Client
	userAgent string
	log       *slog.Logger
	retry     RetryPolicy
	grace     time.Duration

	// Overridable in tests.
	sleep  func(context.Context, time.Duration) error
	jitter func(ceiling time.Duration) time.Duration
}

// New returns a Client for the API at cfg.BaseURL.
func New(cfg Config) (*Client, error) {
	base, err := url.Parse(cfg.BaseURL)
	if err != nil {
		return nil, fmt.Errorf("invalid server URL: %w", err)
	}
	if (base.Scheme != "http" && base.Scheme != "https") || base.Host == "" {
		return nil, fmt.Errorf("invalid server URL %q: want http(s)://host[:port]", cfg.BaseURL)
	}

	c := &Client{
		base:      base,
		http:      cfg.HTTPClient,
		userAgent: cfg.UserAgent,
		log:       cfg.Logger,
		retry:     cfg.Retry,
		grace:     cfg.ShutdownGrace,
		sleep:     sleepContext,
		jitter:    fullJitter,
	}
	if c.http == nil {
		c.http = &http.Client{Timeout: defaultTimeout}
	}
	if c.log == nil {
		c.log = slog.Default()
	}
	if c.retry == (RetryPolicy{}) {
		c.retry = DefaultRetry
	}
	if c.grace <= 0 {
		c.grace = DefaultShutdownGrace
	}
	return c, nil
}

// EnrollRequest is the EnrollRequest schema of the API contract.
type EnrollRequest struct {
	facts.Host
	MachineID    string `json:"machineId"`
	AgentVersion string `json:"agentVersion"`
}

// Enrollment is the API's answer to an enrollment.
type Enrollment struct {
	HostID     uuid.UUID    `json:"hostId"`
	AgentToken secret.Token `json:"agentToken"`
}

// ScanSummary is the part of the API's ScanSummary the agent reports on.
type ScanSummary struct {
	ID     uuid.UUID    `json:"id"`
	Score  *float64     `json:"score"`
	Counts score.Counts `json:"counts"`
	// Duplicate is set when the API had already stored this scan ID (200
	// instead of 201), i.e. an earlier attempt succeeded but its response was
	// lost.
	Duplicate bool `json:"-"`
}

// Enroll registers the host using the shared enrollment token and returns its
// host ID and a fresh agent token. Re-enrolling rotates the host's token.
func (c *Client) Enroll(ctx context.Context, enrollmentToken secret.Token, req EnrollRequest) (Enrollment, error) {
	var e Enrollment
	if _, err := c.post(ctx, enrollPath, enrollmentToken, req, &e); err != nil {
		return Enrollment{}, fmt.Errorf("enroll: %w", err)
	}
	if e.HostID == uuid.Nil() || e.AgentToken == "" {
		return Enrollment{}, errors.New("enroll: response is missing hostId or agentToken")
	}
	return e, nil
}

// SubmitScan uploads a scan report authenticated with the agent token.
// Retries resend the identical report, so the API sees the same scan ID.
func (c *Client) SubmitScan(ctx context.Context, agentToken secret.Token, report scan.Report) (ScanSummary, error) {
	var s ScanSummary
	status, err := c.post(ctx, scansPath, agentToken, report, &s)
	if err != nil {
		return ScanSummary{}, fmt.Errorf("submit scan %s: %w", report.ScanID, err)
	}
	s.Duplicate = status == http.StatusOK
	return s, nil
}

// post sends body as JSON to the endpoint, retrying transient failures, and
// decodes a 200 or 201 response into out. It returns the response status.
func (c *Client) post(ctx context.Context, endpoint string, token secret.Token, body, out any) (int, error) {
	payload, err := json.Marshal(body)
	if err != nil {
		return 0, fmt.Errorf("encode request: %w", err)
	}
	target := c.base.JoinPath(endpoint).String()

	for attempt := 1; ; attempt++ {
		if err := ctx.Err(); err != nil {
			return 0, err
		}
		status, err := c.attempt(ctx, target, token, payload, out)
		if err == nil {
			return status, nil
		}
		if ctx.Err() != nil || !retryable(err) {
			return 0, err
		}
		if attempt >= c.retry.Attempts {
			return 0, fmt.Errorf("giving up after %d attempts: %w", attempt, err)
		}

		delay := c.jitter(c.ceiling(attempt))
		c.log.WarnContext(ctx, "API request failed; will retry",
			slog.String("url", target),
			slog.Int("attempt", attempt),
			slog.Int("maxAttempts", c.retry.Attempts),
			slog.Duration("retryIn", delay),
			slog.Any("error", err))
		if err := c.sleep(ctx, delay); err != nil {
			return 0, err
		}
	}
}

func (c *Client) attempt(ctx context.Context, target string, token secret.Token, payload []byte, out any) (int, error) {
	ctx, cancel := withGrace(ctx, c.grace)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, target, bytes.NewReader(payload))
	if err != nil {
		return 0, fmt.Errorf("build request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json, application/problem+json")
	req.Header.Set("Authorization", "Bearer "+token.Reveal())
	if c.userAgent != "" {
		req.Header.Set("User-Agent", c.userAgent)
	}

	start := time.Now()
	resp, err := c.http.Do(req)
	if err != nil {
		return 0, &transportError{err: err}
	}
	defer resp.Body.Close() //nolint:errcheck // nothing to recover from closing a response body

	data, err := io.ReadAll(io.LimitReader(resp.Body, maxResponseSize))
	if err != nil {
		return 0, &transportError{err: fmt.Errorf("read response: %w", err)}
	}
	c.log.DebugContext(ctx, "API request completed",
		slog.String("url", target),
		slog.Int("status", resp.StatusCode),
		slog.Duration("duration", time.Since(start)))

	switch resp.StatusCode {
	case http.StatusOK, http.StatusCreated:
		if err := json.Unmarshal(data, out); err != nil {
			return 0, fmt.Errorf("decode %d response: %w", resp.StatusCode, err)
		}
		return resp.StatusCode, nil
	default:
		return 0, newStatusError(resp.StatusCode, data)
	}
}

// ceiling returns the backoff ceiling before retry number n (1-based):
// BaseDelay doubled for every earlier retry, capped at MaxDelay.
func (c *Client) ceiling(n int) time.Duration {
	d := c.retry.BaseDelay
	for i := 1; i < n && d < c.retry.MaxDelay; i++ {
		d *= 2
	}
	return min(d, c.retry.MaxDelay)
}

// fullJitter picks a delay uniformly from [0, ceiling], which spreads out
// retries from a fleet of agents that all failed at the same moment.
func fullJitter(ceiling time.Duration) time.Duration {
	if ceiling <= 0 {
		return 0
	}
	return rand.N(ceiling + 1) //nolint:gosec // jitter does not need a cryptographic source
}

// withGrace returns a context that is canceled grace after parent is done, or
// when the returned function is called, whichever comes first.
func withGrace(parent context.Context, grace time.Duration) (context.Context, context.CancelFunc) {
	ctx, cancel := context.WithCancel(context.WithoutCancel(parent))
	go func() {
		select {
		case <-ctx.Done():
			return
		case <-parent.Done():
		}
		timer := time.NewTimer(grace)
		defer timer.Stop()
		select {
		case <-ctx.Done():
		case <-timer.C:
			cancel()
		}
	}()
	return ctx, cancel
}

func sleepContext(ctx context.Context, d time.Duration) error {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-t.C:
		return nil
	}
}

// StatusError is a response with an unexpected HTTP status.
type StatusError struct {
	StatusCode int
	// Title and Detail come from an RFC 9457 problem body, when there is one.
	Title  string
	Detail string
}

func newStatusError(status int, body []byte) *StatusError {
	e := &StatusError{StatusCode: status}
	var problem struct {
		Title  string `json:"title"`
		Detail string `json:"detail"`
	}
	if json.Unmarshal(body, &problem) == nil {
		e.Title, e.Detail = problem.Title, problem.Detail
	}
	return e
}

func (e *StatusError) Error() string {
	msg := fmt.Sprintf("HTTP %d %s", e.StatusCode, http.StatusText(e.StatusCode))
	switch {
	case e.Detail != "":
		msg += ": " + e.Detail
	case e.Title != "":
		msg += ": " + e.Title
	}
	return msg
}

// Is makes errors.Is(err, ErrUnauthorized) true for 401 responses.
func (e *StatusError) Is(target error) bool {
	return target == ErrUnauthorized && e.StatusCode == http.StatusUnauthorized
}

// transportError is a failure to get a complete response from the server.
type transportError struct {
	err error
}

func (e *transportError) Error() string { return e.err.Error() }
func (e *transportError) Unwrap() error { return e.err }

func retryable(err error) bool {
	if se, ok := errors.AsType[*StatusError](err); ok {
		return se.StatusCode >= http.StatusInternalServerError
	}
	_, ok := errors.AsType[*transportError](err)
	return ok
}
