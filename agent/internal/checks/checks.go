// Package checks implements the Driftwatch hardening check catalog.
//
// A Check inspects a Host and produces an Outcome. Checks never fail the scan:
// anything that prevents a verdict (an unreadable file, a value sshd would
// reject) becomes an ERROR outcome whose evidence carries the cause.
package checks

import (
	"fmt"
	"unicode/utf8"
)

// Status is the outcome of one check.
type Status string

// Check statuses, as defined in docs/CONTRACTS.md.
const (
	StatusPass          Status = "PASS"
	StatusFail          Status = "FAIL"
	StatusError         Status = "ERROR"
	StatusNotApplicable Status = "NOT_APPLICABLE"
)

// Severity ranks how much a failing check matters.
type Severity string

// Severities, as defined in docs/CONTRACTS.md.
const (
	SeverityLow      Severity = "low"
	SeverityMedium   Severity = "medium"
	SeverityHigh     Severity = "high"
	SeverityCritical Severity = "critical"
)

// Weight is the severity's contribution to the compliance score.
func (s Severity) Weight() int {
	switch s {
	case SeverityLow:
		return 1
	case SeverityMedium:
		return 2
	case SeverityHigh:
		return 3
	case SeverityCritical:
		return 5
	default:
		return 0
	}
}

// Category groups related checks.
type Category string

// Categories, as defined in docs/CONTRACTS.md.
const (
	CategorySSH        Category = "ssh"
	CategoryAccounts   Category = "accounts"
	CategoryNetwork    Category = "network"
	CategoryKernel     Category = "kernel"
	CategoryFilesystem Category = "filesystem"
)

// Outcome is what evaluating a check produced.
type Outcome struct {
	Status   Status
	Expected string // the compliant value, in words
	Actual   string // the value found on the host
	Evidence string // where the value came from, or why it could not be determined
}

// Check is one entry of the catalog.
type Check struct {
	ID       string
	Title    string
	Category Category
	Severity Severity
	Evaluate func(*Host) Outcome
}

// Result is a check's outcome together with its catalog metadata, in the shape
// the API's CheckResult schema expects.
type Result struct {
	CheckID  string   `json:"checkId"`
	Title    string   `json:"title"`
	Category Category `json:"category"`
	Severity Severity `json:"severity"`
	Status   Status   `json:"status"`
	Expected string   `json:"expected,omitempty"`
	Actual   string   `json:"actual,omitempty"`
	Evidence string   `json:"evidence,omitempty"`
}

// Field limits enforced by the API (docs/CONTRACTS.md, "Validation limits").
const (
	maxValueLen    = 200
	maxEvidenceLen = 1000
)

// Run evaluates every check against h, in catalog order.
func Run(h *Host, catalog []Check) []Result {
	results := make([]Result, 0, len(catalog))
	for _, c := range catalog {
		out := c.evaluate(h)
		results = append(results, Result{
			CheckID:  c.ID,
			Title:    c.Title,
			Category: c.Category,
			Severity: c.Severity,
			Status:   out.Status,
			Expected: truncate(out.Expected, maxValueLen),
			Actual:   truncate(out.Actual, maxValueLen),
			Evidence: truncate(out.Evidence, maxEvidenceLen),
		})
	}
	return results
}

// evaluate runs the check, converting a panic into an ERROR outcome so that a
// bug in one check cannot take down a long-running agent.
func (c Check) evaluate(h *Host) (out Outcome) {
	defer func() {
		if r := recover(); r != nil {
			out = Outcome{Status: StatusError, Evidence: fmt.Sprintf("internal error in check %s: %v", c.ID, r)}
		}
	}()
	return c.Evaluate(h)
}

// truncate shortens s to at most limit bytes without splitting a UTF-8
// sequence. Limiting bytes also bounds the length in UTF-16 code units, which
// is how the API measures strings.
func truncate(s string, limit int) string {
	if len(s) <= limit {
		return s
	}
	const ellipsis = "..."
	cut := limit - len(ellipsis)
	for cut > 0 && !utf8.RuneStart(s[cut]) {
		cut--
	}
	return s[:cut] + ellipsis
}

func judge(ok bool, expected, actual, evidence string) Outcome {
	status := StatusFail
	if ok {
		status = StatusPass
	}
	return Outcome{Status: status, Expected: expected, Actual: actual, Evidence: evidence}
}

func notApplicable(evidence string) Outcome {
	return Outcome{Status: StatusNotApplicable, Evidence: evidence}
}

func errored(expected string, err error) Outcome {
	return Outcome{Status: StatusError, Expected: expected, Evidence: err.Error()}
}
