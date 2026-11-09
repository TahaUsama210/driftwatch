// Package scan runs the check catalog against a host and assembles the report
// that is submitted to the API.
package scan

import (
	"fmt"
	"time"
	"uuid"

	"github.com/TahaUsama210/driftwatch/agent/internal/checks"
	"github.com/TahaUsama210/driftwatch/agent/internal/facts"
	"github.com/TahaUsama210/driftwatch/agent/internal/hostfs"
)

// Report is the ScanReport schema of the API contract.
type Report struct {
	ScanID       uuid.UUID       `json:"scanId"`
	AgentVersion string          `json:"agentVersion"`
	StartedAt    time.Time       `json:"startedAt"`
	FinishedAt   time.Time       `json:"finishedAt"`
	Host         facts.Host      `json:"host"`
	Results      []checks.Result `json:"results"`
}

// Scanner produces reports for one host.
type Scanner struct {
	Root         hostfs.FS
	Proc         hostfs.FS
	Facts        facts.Collector
	Catalog      []checks.Check
	AgentVersion string
	// Now returns the current time; nil means time.Now.
	Now func() time.Time
}

// Scan collects facts, evaluates every check and returns the report. Each
// report gets a fresh random scan ID, which the API uses to deduplicate
// retried submissions.
func (s *Scanner) Scan() (Report, error) {
	started := s.now()
	host, err := s.Facts.Collect()
	if err != nil {
		return Report{}, fmt.Errorf("collect host facts: %w", err)
	}
	results := checks.Run(checks.NewHost(s.Root, s.Proc), s.Catalog)
	return Report{
		ScanID:       uuid.NewV4(),
		AgentVersion: s.AgentVersion,
		StartedAt:    started,
		FinishedAt:   s.now(),
		Host:         host,
		Results:      results,
	}, nil
}

// now returns the current time in UTC, so it encodes as RFC 3339 with a "Z"
// suffix as the contract requires, truncated to milliseconds.
func (s *Scanner) now() time.Time {
	now := time.Now
	if s.Now != nil {
		now = s.Now
	}
	return now().UTC().Truncate(time.Millisecond)
}
