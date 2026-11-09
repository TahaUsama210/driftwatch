package client

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"uuid"

	"github.com/TahaUsama210/driftwatch/agent/internal/checks"
	"github.com/TahaUsama210/driftwatch/agent/internal/facts"
	"github.com/TahaUsama210/driftwatch/agent/internal/scan"
	"github.com/TahaUsama210/driftwatch/agent/internal/secret"
)

const (
	enrollmentToken = secret.Token("enroll-secret-0123456789abcdef")
	agentToken      = secret.Token("dwa_agent-secret-token")
	issuedToken     = "dwa_issued-secret-token"
)

// request is what the fake API recorded about one call.
type request struct {
	method, path, auth, contentType, userAgent string
	body                                       []byte
}

// fakeAPI serves a scripted sequence of responses and records every request.
// Once the script is exhausted it repeats the last response.
type fakeAPI struct {
	t      *testing.T
	server *httptest.Server

	mu        sync.Mutex
	responses []response
	requests  []request
}

type response struct {
	status int
	body   string
}

func newFakeAPI(t *testing.T, responses ...response) *fakeAPI {
	t.Helper()
	f := &fakeAPI{t: t, responses: responses}
	f.server = httptest.NewServer(http.HandlerFunc(f.serve))
	t.Cleanup(f.server.Close)
	return f
}

func (f *fakeAPI) serve(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(r.Body)
	if err != nil {
		f.t.Errorf("read request body: %v", err)
	}
	f.mu.Lock()
	f.requests = append(f.requests, request{
		method: r.Method, path: r.URL.Path, auth: r.Header.Get("Authorization"),
		contentType: r.Header.Get("Content-Type"), userAgent: r.Header.Get("User-Agent"), body: body,
	})
	resp := f.responses[min(len(f.requests), len(f.responses))-1]
	f.mu.Unlock()

	if resp.status >= 400 {
		w.Header().Set("Content-Type", "application/problem+json")
	} else {
		w.Header().Set("Content-Type", "application/json")
	}
	w.WriteHeader(resp.status)
	if _, err := io.WriteString(w, resp.body); err != nil {
		f.t.Errorf("write response: %v", err)
	}
}

func (f *fakeAPI) recorded() []request {
	f.mu.Lock()
	defer f.mu.Unlock()
	return slices.Clone(f.requests)
}

// testClient returns a client for baseURL that does not sleep and records the
// backoff ceilings it would have waited for. Logs go to logs at debug level.
func testClient(t *testing.T, baseURL string, logs io.Writer) (*Client, *[]time.Duration) {
	t.Helper()
	if logs == nil {
		logs = io.Discard
	}
	c, err := New(Config{
		BaseURL:   baseURL,
		UserAgent: "driftwatch-agent/test",
		Logger:    slog.New(slog.NewJSONHandler(logs, &slog.HandlerOptions{Level: slog.LevelDebug})),
	})
	if err != nil {
		t.Fatal(err)
	}
	var waits []time.Duration
	c.jitter = func(ceiling time.Duration) time.Duration { return ceiling }
	c.sleep = func(_ context.Context, d time.Duration) error {
		waits = append(waits, d)
		return nil
	}
	return c, &waits
}

func testReport() scan.Report {
	return scan.Report{
		ScanID:       uuid.NewV4(),
		AgentVersion: "test",
		StartedAt:    time.Date(2026, 11, 3, 14, 5, 0, 0, time.UTC),
		FinishedAt:   time.Date(2026, 11, 3, 14, 5, 1, 0, time.UTC),
		Host:         facts.Host{Hostname: "web-1", OS: facts.OS{ID: "ubuntu", PrettyName: "Ubuntu"}, Kernel: "6.8", Arch: "amd64"},
		Results: []checks.Result{{
			CheckID: "ssh.permit_root_login", Title: "SSH root login is disabled",
			Category: checks.CategorySSH, Severity: checks.SeverityHigh, Status: checks.StatusPass,
		}},
	}
}

const summaryBody = `{"id":"%s","hostId":"9b2f8a1e-8f0a-4c9e-9d38-0d4c8b5f2a11","agentVersion":"test",
	"startedAt":"2026-11-03T14:05:00Z","finishedAt":"2026-11-03T14:05:01Z","receivedAt":"2026-11-03T14:05:01Z",
	"score":87.5,"counts":{"pass":7,"fail":1,"error":0,"notApplicable":2}}`

func summaryFor(r scan.Report) string {
	return strings.Replace(summaryBody, "%s", r.ScanID.String(), 1)
}

func TestNewRejectsInvalidServerURLs(t *testing.T) {
	for _, raw := range []string{"", "api:8080", "ftp://api", "http://", "http://bad host"} {
		if _, err := New(Config{BaseURL: raw}); err == nil {
			t.Errorf("New(%q) succeeded, want an error", raw)
		}
	}
}

func TestEnrollSendsFactsAndReturnsCredentials(t *testing.T) {
	hostID := uuid.NewV4()
	api := newFakeAPI(t, response{http.StatusCreated, `{"hostId":"` + hostID.String() + `","agentToken":"` + issuedToken + `"}`})
	c, _ := testClient(t, api.server.URL+"/", nil)

	got, err := c.Enroll(t.Context(), enrollmentToken, EnrollRequest{
		Host:         facts.Host{Hostname: "web-1", OS: facts.OS{ID: "ubuntu", Version: "24.04", PrettyName: "Ubuntu 24.04"}, Kernel: "6.8", Arch: "amd64"},
		MachineID:    "4c4c4544004a3510804bb2c04f4e4332",
		AgentVersion: "1.0.0",
	})
	if err != nil {
		t.Fatal(err)
	}
	if got.HostID != hostID || got.AgentToken.Reveal() != issuedToken {
		t.Errorf("Enroll = %+v", got)
	}

	req := api.recorded()[0]
	if req.method != http.MethodPost || req.path != "/api/v1/agents/enroll" {
		t.Errorf("request = %s %s", req.method, req.path)
	}
	if req.auth != "Bearer "+enrollmentToken.Reveal() || req.contentType != "application/json" || req.userAgent != "driftwatch-agent/test" {
		t.Errorf("headers: auth=%q content-type=%q user-agent=%q", req.auth, req.contentType, req.userAgent)
	}
	var body map[string]any
	if err := json.Unmarshal(req.body, &body); err != nil {
		t.Fatal(err)
	}
	for key, want := range map[string]any{"hostname": "web-1", "kernel": "6.8", "arch": "amd64", "machineId": "4c4c4544004a3510804bb2c04f4e4332", "agentVersion": "1.0.0"} {
		if body[key] != want {
			t.Errorf("body[%q] = %v, want %v", key, body[key], want)
		}
	}
	if os, _ := body["os"].(map[string]any); os["id"] != "ubuntu" || os["version"] != "24.04" || os["prettyName"] != "Ubuntu 24.04" {
		t.Errorf("body.os = %v", body["os"])
	}
}

func TestEnrollAcceptsReEnrollment(t *testing.T) {
	api := newFakeAPI(t, response{http.StatusOK, `{"hostId":"` + uuid.NewV4().String() + `","agentToken":"` + issuedToken + `"}`})
	c, _ := testClient(t, api.server.URL, nil)

	if _, err := c.Enroll(t.Context(), enrollmentToken, EnrollRequest{}); err != nil {
		t.Errorf("Enroll with 200 = %v, want success", err)
	}
}

func TestEnrollRejectsIncompleteResponses(t *testing.T) {
	for _, body := range []string{`{}`, `{"hostId":"` + uuid.NewV4().String() + `"}`, `{"agentToken":"x"}`, `not json`} {
		api := newFakeAPI(t, response{http.StatusCreated, body})
		c, _ := testClient(t, api.server.URL, nil)
		if _, err := c.Enroll(t.Context(), enrollmentToken, EnrollRequest{}); err == nil {
			t.Errorf("Enroll accepted response %s", body)
		}
	}
}

func TestSubmitScan(t *testing.T) {
	report := testReport()
	tests := []struct {
		name          string
		status        int
		wantDuplicate bool
	}{
		{"new scan", http.StatusCreated, false},
		{"duplicate submission", http.StatusOK, true},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			api := newFakeAPI(t, response{tt.status, summaryFor(report)})
			c, _ := testClient(t, api.server.URL, nil)

			got, err := c.SubmitScan(t.Context(), agentToken, report)
			if err != nil {
				t.Fatal(err)
			}
			if got.ID != report.ScanID || got.Score == nil || *got.Score != 87.5 || got.Counts.Pass != 7 || got.Duplicate != tt.wantDuplicate {
				t.Errorf("SubmitScan = %+v", got)
			}
			req := api.recorded()[0]
			if req.path != "/api/v1/scans" || req.auth != "Bearer "+agentToken.Reveal() {
				t.Errorf("request = %s with auth %q", req.path, req.auth)
			}
			var sent scan.Report
			if err := json.Unmarshal(req.body, &sent); err != nil || sent.ScanID != report.ScanID || len(sent.Results) != 1 {
				t.Errorf("sent report = %+v, %v", sent, err)
			}
		})
	}
}

func TestSubmitScanRetriesTransientFailuresWithTheSameScanID(t *testing.T) {
	report := testReport()
	api := newFakeAPI(t,
		response{http.StatusServiceUnavailable, `{"title":"Service Unavailable","status":503}`},
		response{http.StatusBadGateway, ``},
		response{http.StatusCreated, summaryFor(report)},
	)
	c, waits := testClient(t, api.server.URL, nil)

	if _, err := c.SubmitScan(t.Context(), agentToken, report); err != nil {
		t.Fatal(err)
	}

	requests := api.recorded()
	if len(requests) != 3 {
		t.Fatalf("server saw %d requests, want 3", len(requests))
	}
	for i, req := range requests {
		if !bytes.Equal(req.body, requests[0].body) {
			t.Errorf("attempt %d sent a different body", i+1)
		}
	}
	if want := []time.Duration{time.Second, 2 * time.Second}; !slices.Equal(*waits, want) {
		t.Errorf("backoff ceilings = %v, want %v", *waits, want)
	}
}

func TestSubmitScanGivesUpAfterFiveAttempts(t *testing.T) {
	api := newFakeAPI(t, response{http.StatusInternalServerError, `{"title":"Internal Server Error","status":500,"detail":"database unavailable"}`})
	c, waits := testClient(t, api.server.URL, nil)

	_, err := c.SubmitScan(t.Context(), agentToken, testReport())

	if err == nil || !strings.Contains(err.Error(), "giving up after 5 attempts: HTTP 500 Internal Server Error: database unavailable") {
		t.Errorf("error = %v", err)
	}
	if n := len(api.recorded()); n != 5 {
		t.Errorf("server saw %d requests, want 5", n)
	}
	if want := []time.Duration{time.Second, 2 * time.Second, 4 * time.Second, 8 * time.Second}; !slices.Equal(*waits, want) {
		t.Errorf("backoff ceilings = %v, want %v", *waits, want)
	}
}

func TestSubmitScanRetriesTransportErrors(t *testing.T) {
	api := newFakeAPI(t)
	api.server.Close() // connection refused from now on
	c, waits := testClient(t, api.server.URL, nil)

	_, err := c.SubmitScan(t.Context(), agentToken, testReport())

	if err == nil || !strings.Contains(err.Error(), "giving up after 5 attempts") {
		t.Errorf("error = %v", err)
	}
	if len(*waits) != 4 {
		t.Errorf("waited %d times, want 4", len(*waits))
	}
}

func TestClientErrorsAreNotRetried(t *testing.T) {
	tests := []struct {
		status       int
		body         string
		wantErr      string
		unauthorized bool
	}{
		{http.StatusBadRequest, `{"title":"Bad Request","status":400,"detail":"results[0].checkId: must match pattern"}`, "HTTP 400 Bad Request: results[0].checkId: must match pattern", false},
		{http.StatusUnauthorized, `{"title":"Unauthorized","status":401}`, "HTTP 401 Unauthorized: Unauthorized", true},
		{http.StatusConflict, `{"title":"Conflict","status":409,"detail":"scanId belongs to another host"}`, "HTTP 409 Conflict: scanId belongs to another host", false},
		{http.StatusRequestEntityTooLarge, ``, "HTTP 413 Request Entity Too Large", false},
	}
	for _, tt := range tests {
		t.Run(http.StatusText(tt.status), func(t *testing.T) {
			api := newFakeAPI(t, response{tt.status, tt.body})
			c, waits := testClient(t, api.server.URL, nil)

			_, err := c.SubmitScan(t.Context(), agentToken, testReport())

			if err == nil || !strings.Contains(err.Error(), tt.wantErr) {
				t.Errorf("error = %v, want it to contain %q", err, tt.wantErr)
			}
			if errors.Is(err, ErrUnauthorized) != tt.unauthorized {
				t.Errorf("errors.Is(err, ErrUnauthorized) = %v, want %v", !tt.unauthorized, tt.unauthorized)
			}
			if se, ok := errors.AsType[*StatusError](err); !ok || se.StatusCode != tt.status {
				t.Errorf("error is not a *StatusError with status %d: %v", tt.status, err)
			}
			if n := len(api.recorded()); n != 1 || len(*waits) != 0 {
				t.Errorf("server saw %d requests and the client waited %d times, want 1 and 0", n, len(*waits))
			}
		})
	}
}

func TestCancellationStopsRetrying(t *testing.T) {
	api := newFakeAPI(t, response{http.StatusServiceUnavailable, ``})
	c, _ := testClient(t, api.server.URL, nil)
	ctx, cancel := context.WithCancel(t.Context())
	c.sleep = func(ctx context.Context, d time.Duration) error {
		cancel()
		return sleepContext(ctx, d)
	}

	_, err := c.SubmitScan(ctx, agentToken, testReport())

	if !errors.Is(err, context.Canceled) {
		t.Errorf("error = %v, want context.Canceled", err)
	}
	if n := len(api.recorded()); n != 1 {
		t.Errorf("server saw %d requests after cancellation, want 1", n)
	}
}

func TestTokensNeverAppearInLogs(t *testing.T) {
	report := testReport()
	api := newFakeAPI(t,
		response{http.StatusServiceUnavailable, ``},
		response{http.StatusCreated, `{"hostId":"` + uuid.NewV4().String() + `","agentToken":"` + issuedToken + `"}`},
		response{http.StatusServiceUnavailable, ``},
		response{http.StatusCreated, summaryFor(report)},
		response{http.StatusUnauthorized, `{"title":"Unauthorized","status":401}`},
	)
	var logs bytes.Buffer
	c, _ := testClient(t, api.server.URL, &logs)

	e, err := c.Enroll(t.Context(), enrollmentToken, EnrollRequest{})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := c.SubmitScan(t.Context(), e.AgentToken, report); err != nil {
		t.Fatal(err)
	}
	_, err = c.SubmitScan(t.Context(), agentToken, report)
	c.log.Info("enrollment", slog.Any("enrollment", e), slog.Any("error", err))

	if logs.Len() == 0 {
		t.Fatal("expected debug and retry log records")
	}
	for _, tok := range []string{enrollmentToken.Reveal(), agentToken.Reveal(), issuedToken} {
		if strings.Contains(logs.String(), tok) || strings.Contains(err.Error(), tok) {
			t.Errorf("token %q leaked into logs or errors:\n%s", tok, logs.String())
		}
	}
}

func TestCeilingDoublesUpToTheCap(t *testing.T) {
	c := &Client{retry: RetryPolicy{Attempts: 10, BaseDelay: time.Second, MaxDelay: time.Minute}}
	var got []time.Duration
	for n := 1; n <= 8; n++ {
		got = append(got, c.ceiling(n))
	}
	want := []time.Duration{1, 2, 4, 8, 16, 32, 60, 60}
	for i := range want {
		want[i] *= time.Second
	}
	if !slices.Equal(got, want) {
		t.Errorf("ceilings = %v, want %v", got, want)
	}
}

func TestFullJitterStaysWithinTheCeiling(t *testing.T) {
	for range 1000 {
		if d := fullJitter(time.Second); d < 0 || d > time.Second {
			t.Fatalf("fullJitter(1s) = %v", d)
		}
	}
	if d := fullJitter(0); d != 0 {
		t.Errorf("fullJitter(0) = %v, want 0", d)
	}
}

func TestSleepContext(t *testing.T) {
	if err := sleepContext(t.Context(), time.Millisecond); err != nil {
		t.Errorf("sleepContext = %v", err)
	}
	ctx, cancel := context.WithCancel(t.Context())
	cancel()
	if err := sleepContext(ctx, time.Hour); !errors.Is(err, context.Canceled) {
		t.Errorf("sleepContext on a canceled context = %v", err)
	}
}

func TestNoRequestIsSentAfterCancellation(t *testing.T) {
	api := newFakeAPI(t, response{http.StatusCreated, `{}`})
	c, _ := testClient(t, api.server.URL, nil)
	ctx, cancel := context.WithCancel(t.Context())
	cancel()

	if _, err := c.SubmitScan(ctx, agentToken, testReport()); !errors.Is(err, context.Canceled) {
		t.Errorf("error = %v, want context.Canceled", err)
	}
	if n := len(api.recorded()); n != 0 {
		t.Errorf("server saw %d requests on a canceled context", n)
	}
}

// blockingAPI holds each request until release is closed (or the client
// gives up) and reports arrivals on arrived.
func blockingAPI(t *testing.T, release <-chan struct{}) (url string, arrived <-chan struct{}) {
	t.Helper()
	ch := make(chan struct{}, 1)
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// net/http only notices that the client has gone away (and cancels
		// r.Context()) once the request body has been read, so read it first
		// as a real handler would.
		if _, err := io.Copy(io.Discard, r.Body); err != nil {
			t.Errorf("read request body: %v", err)
		}
		ch <- struct{}{}
		select {
		case <-release:
			w.WriteHeader(http.StatusCreated)
			_, _ = io.WriteString(w, `{"id":"`+uuid.NewV4().String()+`","score":null,"counts":{"pass":0,"fail":0,"error":0,"notApplicable":0}}`)
		case <-r.Context().Done():
		}
	}))
	t.Cleanup(server.Close)
	return server.URL, ch
}

func TestAnInFlightRequestMayFinishAfterCancellation(t *testing.T) {
	release := make(chan struct{})
	url, arrived := blockingAPI(t, release)
	c, _ := testClient(t, url, nil)
	ctx, cancel := context.WithCancel(t.Context())

	go func() {
		<-arrived
		cancel() // shutdown begins while the request is on the wire
		close(release)
	}()
	if _, err := c.SubmitScan(ctx, agentToken, testReport()); err != nil {
		t.Errorf("SubmitScan = %v, want the in-flight request to complete", err)
	}
}

func TestAnInFlightRequestIsAbandonedAfterTheGracePeriod(t *testing.T) {
	url, arrived := blockingAPI(t, make(chan struct{})) // never released
	c, _ := testClient(t, url, nil)
	c.grace = 20 * time.Millisecond
	ctx, cancel := context.WithCancel(t.Context())

	go func() {
		<-arrived
		cancel()
	}()
	_, err := c.SubmitScan(ctx, agentToken, testReport())
	if !errors.Is(err, context.Canceled) {
		t.Errorf("error = %v, want context.Canceled once the grace period expires", err)
	}
}
