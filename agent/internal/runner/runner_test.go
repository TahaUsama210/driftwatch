package runner_test

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"uuid"

	"github.com/TahaUsama210/driftwatch/agent/internal/checks"
	"github.com/TahaUsama210/driftwatch/agent/internal/client"
	"github.com/TahaUsama210/driftwatch/agent/internal/facts"
	"github.com/TahaUsama210/driftwatch/agent/internal/hostfs/hostfstest"
	"github.com/TahaUsama210/driftwatch/agent/internal/runner"
	"github.com/TahaUsama210/driftwatch/agent/internal/scan"
	"github.com/TahaUsama210/driftwatch/agent/internal/state"
)

const enrollmentToken = "enroll-secret-0123456789abcdef"

// fakeAPI is a small in-memory implementation of the two agent endpoints.
type fakeAPI struct {
	t      *testing.T
	server *httptest.Server
	hostID uuid.UUID

	mu          sync.Mutex
	enrollments []client.EnrollRequest
	validToken  string
	issued      int
	scans       []scanAttempt // every submission, accepted or not
	// enrollStatus, if set, is returned for the next enrollFailures enrollments.
	enrollStatus   int
	enrollFailures int
	rejectScans    bool // answer every submission with 401
	// onScan, if set, runs before a submission is answered.
	onScan func(r *http.Request)
}

type scanAttempt struct {
	id     uuid.UUID
	status int
}

func newFakeAPI(t *testing.T) *fakeAPI {
	t.Helper()
	f := &fakeAPI{t: t, hostID: uuid.NewV4()}
	mux := http.NewServeMux()
	mux.HandleFunc("POST /api/v1/agents/enroll", f.enroll)
	mux.HandleFunc("POST /api/v1/scans", f.submit)
	f.server = httptest.NewServer(mux)
	t.Cleanup(f.server.Close)
	return f
}

func (f *fakeAPI) enroll(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.enrollFailures > 0 {
		f.enrollFailures--
		w.WriteHeader(f.enrollStatus)
		return
	}
	if r.Header.Get("Authorization") != "Bearer "+enrollmentToken {
		w.WriteHeader(http.StatusUnauthorized)
		return
	}
	var req client.EnrollRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		f.t.Errorf("decode enroll request: %v", err)
	}
	f.enrollments = append(f.enrollments, req)
	f.issued++
	f.validToken = fmt.Sprintf("dwa_token-%d", f.issued) // rotates any previous token
	writeJSON(f.t, w, http.StatusCreated, map[string]string{"hostId": f.hostID.String(), "agentToken": f.validToken})
}

func (f *fakeAPI) submit(w http.ResponseWriter, r *http.Request) {
	var report scan.Report
	if err := json.NewDecoder(r.Body).Decode(&report); err != nil {
		f.t.Errorf("decode scan report: %v", err)
	}
	f.mu.Lock()
	hook := f.onScan
	f.mu.Unlock()
	if hook != nil {
		hook(r)
	}

	f.mu.Lock()
	defer f.mu.Unlock()
	status := http.StatusCreated
	if f.rejectScans || r.Header.Get("Authorization") != "Bearer "+f.validToken {
		status = http.StatusUnauthorized
	}
	f.scans = append(f.scans, scanAttempt{id: report.ScanID, status: status})
	if status != http.StatusCreated {
		w.WriteHeader(status)
		return
	}
	writeJSON(f.t, w, status, map[string]any{
		"id": report.ScanID, "hostId": f.hostID, "score": 50.0,
		"counts": map[string]int{"pass": 1, "fail": 1, "error": 0, "notApplicable": 0},
	})
}

func writeJSON(t *testing.T, w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if err := json.NewEncoder(w).Encode(v); err != nil {
		t.Errorf("write response: %v", err)
	}
}

func (f *fakeAPI) snapshot() (enrollments []client.EnrollRequest, scans []scanAttempt) {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]client.EnrollRequest(nil), f.enrollments...), append([]scanAttempt(nil), f.scans...)
}

// logRecorder captures log output and announces each message on a channel.
type logRecorder struct {
	mu   sync.Mutex
	buf  bytes.Buffer
	msgs chan string
}

func (l *logRecorder) Write(p []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.buf.Write(p)
}

func (l *logRecorder) String() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.buf.String()
}

type notifyingHandler struct {
	slog.Handler
	msgs chan<- string
}

func (h notifyingHandler) Handle(ctx context.Context, r slog.Record) error {
	err := h.Handler.Handle(ctx, r)
	h.msgs <- r.Message
	return err
}

func (h notifyingHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	return notifyingHandler{h.Handler.WithAttrs(attrs), h.msgs}
}

func (h notifyingHandler) WithGroup(name string) slog.Handler {
	return notifyingHandler{h.Handler.WithGroup(name), h.msgs}
}

// waitFor blocks until a log message containing want is recorded.
func (l *logRecorder) waitFor(t *testing.T, want string) {
	t.Helper()
	timeout := time.After(10 * time.Second)
	for {
		select {
		case msg := <-l.msgs:
			if strings.Contains(msg, want) {
				return
			}
		case <-timeout:
			t.Fatalf("timed out waiting for log message %q; logs:\n%s", want, l.String())
		}
	}
}

type harness struct {
	api    *fakeAPI
	runner *runner.Runner
	store  *state.Store
	logs   *logRecorder
	logger *slog.Logger
}

// useClient replaces the harness's API client with one configured by cfg.
func (h *harness) useClient(t *testing.T, cfg client.Config) {
	t.Helper()
	cfg.BaseURL, cfg.Logger = h.api.server.URL, h.logger
	c, err := client.New(cfg)
	if err != nil {
		t.Fatal(err)
	}
	h.runner.Client = c
}

func newHarness(t *testing.T, api *fakeAPI) *harness {
	t.Helper()
	logs := &logRecorder{msgs: make(chan string, 1000)}
	logger := slog.New(notifyingHandler{slog.NewJSONHandler(logs, &slog.HandlerOptions{Level: slog.LevelDebug}), logs.msgs})
	c, err := client.New(client.Config{
		BaseURL: api.server.URL,
		Logger:  logger,
		Retry:   client.RetryPolicy{Attempts: 2, BaseDelay: time.Millisecond, MaxDelay: time.Millisecond},
	})
	if err != nil {
		t.Fatal(err)
	}

	root := hostfstest.MapFS{"/etc/hostname": {Data: []byte("web-1\n")}}
	proc := hostfstest.MapFS{"/sys/kernel/osrelease": {Data: []byte("6.8.0\n")}}
	store := state.NewStore(t.TempDir())
	return &harness{
		api:    api,
		store:  store,
		logs:   logs,
		logger: logger,
		runner: &runner.Runner{
			Client: c,
			Store:  store,
			Scanner: &scan.Scanner{
				Root: root, Proc: proc,
				Facts:        facts.Collector{Root: root, Proc: proc},
				Catalog:      checks.Catalog(),
				AgentVersion: "test",
			},
			Logger:          logger,
			Interval:        time.Hour,
			EnrollmentToken: enrollmentToken,
		},
	}
}

// start runs the agent in the background and returns a function that stops
// it and returns Run's result.
func (h *harness) start(t *testing.T) (stop func() error) {
	t.Helper()
	ctx, cancel := context.WithCancel(t.Context())
	done := make(chan error, 1)
	go func() { done <- h.runner.Run(ctx) }()
	return func() error {
		cancel()
		select {
		case err := <-done:
			return err
		case <-time.After(10 * time.Second):
			t.Fatal("Run did not return after cancellation")
			return nil
		}
	}
}

// run runs the agent until it returns on its own.
func (h *harness) run(t *testing.T) error {
	t.Helper()
	ctx, cancel := context.WithTimeout(t.Context(), 10*time.Second)
	defer cancel()
	return h.runner.Run(ctx)
}

func (h *harness) loadState(t *testing.T) state.State {
	t.Helper()
	st, err := h.store.Load()
	if err != nil {
		t.Fatal(err)
	}
	return st
}

func TestRunEnrollsThenScansImmediatelyAndEveryInterval(t *testing.T) {
	h := newHarness(t, newFakeAPI(t))
	h.runner.Interval = 20 * time.Millisecond

	stop := h.start(t)
	for range 3 {
		h.logs.waitFor(t, "scan submitted")
	}
	if err := stop(); err != nil {
		t.Fatalf("Run = %v, want nil after cancellation", err)
	}

	enrollments, scans := h.api.snapshot()
	if len(enrollments) != 1 {
		t.Fatalf("enrolled %d times, want 1", len(enrollments))
	}
	st := h.loadState(t)
	if st.HostID != h.api.hostID || st.AgentToken.Reveal() != "dwa_token-1" {
		t.Errorf("saved state = %+v, want the issued credentials", st)
	}
	// The fake host has no /etc/machine-id, so a generated ID is persisted
	// and used for enrollment.
	if _, err := uuid.Parse(st.MachineID); err != nil || enrollments[0].MachineID != st.MachineID {
		t.Errorf("machine ID: saved %q, enrolled with %q", st.MachineID, enrollments[0].MachineID)
	}
	if enrollments[0].Hostname != "web-1" || enrollments[0].AgentVersion != "test" {
		t.Errorf("enroll request = %+v", enrollments[0])
	}
	seen := map[uuid.UUID]bool{}
	for _, s := range scans {
		if s.status != http.StatusCreated || seen[s.id] {
			t.Errorf("scan %s: status %d, repeated %v", s.id, s.status, seen[s.id])
		}
		seen[s.id] = true
	}
}

func TestRunUsesSavedCredentialsAndMachineID(t *testing.T) {
	api := newFakeAPI(t)
	api.validToken = "dwa_saved"
	h := newHarness(t, api)
	saved := state.State{HostID: api.hostID, AgentToken: "dwa_saved", MachineID: "persisted-machine-id"}
	if err := h.store.Save(saved); err != nil {
		t.Fatal(err)
	}

	stop := h.start(t)
	h.logs.waitFor(t, "scan submitted")
	if err := stop(); err != nil {
		t.Fatal(err)
	}

	if enrollments, _ := api.snapshot(); len(enrollments) != 0 {
		t.Errorf("enrolled %d times with saved credentials, want 0", len(enrollments))
	}
	if st := h.loadState(t); st != saved {
		t.Errorf("state changed to %+v", st)
	}
}

func TestRunReEnrollsOnceWhenTheTokenIsRejected(t *testing.T) {
	api := newFakeAPI(t)
	h := newHarness(t, api)
	if err := h.store.Save(state.State{HostID: api.hostID, AgentToken: "dwa_rotated-away", MachineID: "persisted-machine-id"}); err != nil {
		t.Fatal(err)
	}

	stop := h.start(t)
	h.logs.waitFor(t, "scan submitted")
	if err := stop(); err != nil {
		t.Fatal(err)
	}

	enrollments, scans := api.snapshot()
	if len(enrollments) != 1 || enrollments[0].MachineID != "persisted-machine-id" {
		t.Fatalf("enrollments = %+v, want one with the persisted machine ID", enrollments)
	}
	if len(scans) != 2 || scans[0].status != http.StatusUnauthorized || scans[1].status != http.StatusCreated {
		t.Fatalf("scan attempts = %+v, want a 401 then a 201", scans)
	}
	if scans[0].id != scans[1].id {
		t.Errorf("resubmission used scan ID %s, want the original %s", scans[1].id, scans[0].id)
	}
	if st := h.loadState(t); st.AgentToken.Reveal() != "dwa_token-1" {
		t.Errorf("saved token = %q, want the re-issued token", st.AgentToken.Reveal())
	}
}

func TestRunReEnrollsAtMostOncePerScan(t *testing.T) {
	api := newFakeAPI(t)
	api.rejectScans = true
	h := newHarness(t, api)

	stop := h.start(t)
	h.logs.waitFor(t, "scan submission failed")
	if err := stop(); err != nil {
		t.Fatal(err)
	}

	enrollments, scans := api.snapshot()
	// One enrollment because there were no credentials, one re-enrollment
	// after the first 401, and no further attempts.
	if len(enrollments) != 2 || len(scans) != 2 {
		t.Errorf("enrollments = %d, scan attempts = %d; want 2 and 2", len(enrollments), len(scans))
	}
}

func TestRunStopsWhenEnrollmentIsRejected(t *testing.T) {
	h := newHarness(t, newFakeAPI(t))
	h.runner.EnrollmentToken = "wrong-token"

	err := h.run(t)

	if err == nil || !strings.Contains(err.Error(), "the API rejected enrollment") {
		t.Errorf("Run = %v, want an enrollment rejection", err)
	}
	if !errors.Is(err, client.ErrUnauthorized) {
		t.Errorf("Run error %v does not wrap client.ErrUnauthorized", err)
	}
}

func TestRunStopsWhenItMustEnrollWithoutAnEnrollmentToken(t *testing.T) {
	h := newHarness(t, newFakeAPI(t))
	h.runner.EnrollmentToken = ""

	if err := h.run(t); err == nil || !strings.Contains(err.Error(), "no enrollment token") {
		t.Errorf("Run = %v, want a missing enrollment token error", err)
	}
}

func TestRunKeepsTryingWhileTheAPIIsUnavailable(t *testing.T) {
	api := newFakeAPI(t)
	api.enrollStatus, api.enrollFailures = http.StatusServiceUnavailable, 2 // the whole first cycle
	h := newHarness(t, api)
	h.runner.Interval = 20 * time.Millisecond

	stop := h.start(t)
	h.logs.waitFor(t, "enrollment failed; will retry at next interval")
	h.logs.waitFor(t, "scan submitted")
	if err := stop(); err != nil {
		t.Fatal(err)
	}
}

func TestRunKeepsGoingWhenAScanCannotBeTaken(t *testing.T) {
	api := newFakeAPI(t)
	api.validToken = "dwa_saved"
	h := newHarness(t, api)
	if err := h.store.Save(state.State{HostID: api.hostID, AgentToken: "dwa_saved", MachineID: "m"}); err != nil {
		t.Fatal(err)
	}
	h.runner.Scanner.Facts.Hostname = func() (string, error) { return "", fmt.Errorf("uname failed") }

	stop := h.start(t)
	h.logs.waitFor(t, "scan failed; will retry at next interval")
	if err := stop(); err != nil {
		t.Fatal(err)
	}
}

func TestRunFinishesAnInFlightSubmissionOnShutdown(t *testing.T) {
	api := newFakeAPI(t)
	arrived, release := make(chan struct{}), make(chan struct{})
	api.onScan = func(*http.Request) {
		close(arrived)
		<-release
	}
	h := newHarness(t, api)

	ctx, cancel := context.WithCancel(t.Context())
	done := make(chan error, 1)
	go func() { done <- h.runner.Run(ctx) }()
	<-arrived
	cancel() // SIGTERM while the submission is on the wire
	close(release)

	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if _, scans := api.snapshot(); len(scans) != 1 || scans[0].status != http.StatusCreated {
		t.Errorf("scans = %+v, want the in-flight submission to complete", scans)
	}
	if !strings.Contains(h.logs.String(), "scan submitted") {
		t.Errorf("logs do not record the submission:\n%s", h.logs.String())
	}
}

func TestRunAbandonsASubmissionThatOutlastsTheGracePeriod(t *testing.T) {
	api := newFakeAPI(t)
	arrived := make(chan struct{})
	api.onScan = func(r *http.Request) {
		close(arrived)
		<-r.Context().Done() // never answers
	}
	h := newHarness(t, api)
	h.useClient(t, client.Config{ShutdownGrace: 50 * time.Millisecond})

	ctx, cancel := context.WithCancel(t.Context())
	done := make(chan error, 1)
	go func() { done <- h.runner.Run(ctx) }()
	<-arrived
	cancel()

	select {
	case err := <-done:
		if err != nil {
			t.Fatal(err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("Run did not give up on the submission after the grace period")
	}
	if !strings.Contains(h.logs.String(), "scan submission interrupted by shutdown") {
		t.Errorf("logs do not record the interruption:\n%s", h.logs.String())
	}
}

func TestRunNeverLogsTokens(t *testing.T) {
	api := newFakeAPI(t)
	h := newHarness(t, api)
	if err := h.store.Save(state.State{HostID: api.hostID, AgentToken: "dwa_rotated-away", MachineID: "m"}); err != nil {
		t.Fatal(err)
	}

	stop := h.start(t)
	h.logs.waitFor(t, "scan submitted")
	if err := stop(); err != nil {
		t.Fatal(err)
	}

	for _, tok := range []string{enrollmentToken, "dwa_rotated-away", "dwa_token-1"} {
		if strings.Contains(h.logs.String(), tok) {
			t.Errorf("logs contain token %q:\n%s", tok, h.logs.String())
		}
	}
}

func TestRunRejectsANonPositiveInterval(t *testing.T) {
	h := newHarness(t, newFakeAPI(t))
	h.runner.Interval = 0
	if err := h.run(t); err == nil {
		t.Error("Run accepted a zero interval")
	}
}

func TestRunFailsOnCorruptState(t *testing.T) {
	h := newHarness(t, newFakeAPI(t))
	if err := os.WriteFile(h.store.Path(), []byte("{"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := h.run(t); err == nil || !strings.Contains(err.Error(), "parse state") {
		t.Errorf("Run = %v, want a state parse error", err)
	}
}

func TestRunFailsWhenTheMachineIDIsUnreadable(t *testing.T) {
	h := newHarness(t, newFakeAPI(t))
	root, ok := h.runner.Scanner.Root.(hostfstest.MapFS)
	if !ok {
		t.Fatalf("harness root is %T", h.runner.Scanner.Root)
	}
	root["/etc/machine-id"] = hostfstest.File{Err: fs.ErrPermission}

	if err := h.run(t); !errors.Is(err, fs.ErrPermission) {
		t.Errorf("Run = %v, want the machine-id read error", err)
	}
}

func TestRunRetriesEnrollmentWhenFactsAreUnavailable(t *testing.T) {
	h := newHarness(t, newFakeAPI(t))
	h.runner.Scanner.Facts.Hostname = func() (string, error) { return "", fmt.Errorf("uname failed") }

	stop := h.start(t)
	h.logs.waitFor(t, "enrollment failed; will retry at next interval")
	if err := stop(); err != nil {
		t.Fatal(err)
	}
	if enrollments, _ := h.api.snapshot(); len(enrollments) != 0 {
		t.Errorf("enrolled without host facts: %+v", enrollments)
	}
}

func TestShutdownStopsRetryingImmediately(t *testing.T) {
	api := newFakeAPI(t)
	api.enrollStatus, api.enrollFailures = http.StatusServiceUnavailable, 1000
	h := newHarness(t, api)
	// The backoff before the second attempt would be up to an hour.
	h.useClient(t, client.Config{Retry: client.RetryPolicy{Attempts: 5, BaseDelay: time.Hour, MaxDelay: time.Hour}})

	stop := h.start(t)
	h.logs.waitFor(t, "API request failed; will retry")
	began := time.Now()
	if err := stop(); err != nil {
		t.Fatal(err)
	}
	if waited := time.Since(began); waited > 2*time.Second {
		t.Errorf("Run took %s to stop during a backoff wait", waited)
	}
	h.logs.waitFor(t, "enrollment interrupted by shutdown")
}
