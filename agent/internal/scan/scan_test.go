package scan_test

import (
	"encoding/json"
	"strings"
	"testing"
	"time"

	"github.com/TahaUsama210/driftwatch/agent/internal/checks"
	"github.com/TahaUsama210/driftwatch/agent/internal/facts"
	"github.com/TahaUsama210/driftwatch/agent/internal/hostfs/hostfstest"
	"github.com/TahaUsama210/driftwatch/agent/internal/scan"
)

func newScanner(root hostfstest.MapFS) *scan.Scanner {
	proc := hostfstest.MapFS{"/sys/kernel/osrelease": {Data: []byte("6.8.0\n")}}
	clock := time.Date(2026, 11, 3, 14, 5, 0, 123456789, time.FixedZone("CET", 3600))
	return &scan.Scanner{
		Root:         root,
		Proc:         proc,
		Facts:        facts.Collector{Root: root, Proc: proc},
		Catalog:      checks.Catalog(),
		AgentVersion: "1.2.3",
		Now:          func() time.Time { return clock },
	}
}

func TestScanBuildsAReport(t *testing.T) {
	s := newScanner(hostfstest.MapFS{"/etc/hostname": {Data: []byte("web-1\n")}})

	report, err := s.Scan()
	if err != nil {
		t.Fatal(err)
	}

	if report.AgentVersion != "1.2.3" || report.Host.Hostname != "web-1" || report.Host.Kernel != "6.8.0" {
		t.Errorf("report metadata = %+v", report)
	}
	if len(report.Results) != len(checks.Catalog()) {
		t.Errorf("report has %d results, want %d", len(report.Results), len(checks.Catalog()))
	}
	if version := report.ScanID[6] >> 4; version != 4 {
		t.Errorf("scan ID %s is UUID version %d, want 4", report.ScanID, version)
	}

	data, err := json.Marshal(report)
	if err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{`"startedAt":"2026-11-03T13:05:00.123Z"`, `"finishedAt":"2026-11-03T13:05:00.123Z"`, `"scanId":"` + report.ScanID.String() + `"`} {
		if !strings.Contains(string(data), want) {
			t.Errorf("report JSON lacks %s:\n%s", want, data)
		}
	}
}

func TestEveryScanGetsANewID(t *testing.T) {
	s := newScanner(hostfstest.MapFS{"/etc/hostname": {Data: []byte("web-1\n")}})
	first, err := s.Scan()
	if err != nil {
		t.Fatal(err)
	}
	second, err := s.Scan()
	if err != nil {
		t.Fatal(err)
	}
	if first.ScanID == second.ScanID {
		t.Errorf("two scans share ID %s", first.ScanID)
	}
}

func TestScanFailsWhenFactsCannotBeCollected(t *testing.T) {
	if _, err := newScanner(hostfstest.MapFS{}).Scan(); err == nil || !strings.Contains(err.Error(), "collect host facts") {
		t.Errorf("Scan error = %v, want a facts error", err)
	}
}

func TestScanUsesTheWallClockByDefault(t *testing.T) {
	s := newScanner(hostfstest.MapFS{"/etc/hostname": {Data: []byte("web-1\n")}})
	s.Now = nil
	before := time.Now().Add(-time.Second)

	report, err := s.Scan()
	if err != nil {
		t.Fatal(err)
	}
	if report.StartedAt.Before(before) || report.StartedAt.Location() != time.UTC || report.FinishedAt.Before(report.StartedAt) {
		t.Errorf("timestamps started=%v finished=%v", report.StartedAt, report.FinishedAt)
	}
}
