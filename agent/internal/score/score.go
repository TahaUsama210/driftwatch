// Package score computes the compliance score defined in docs/CONTRACTS.md:
//
//	score = 100 × Σ weight(PASS) / Σ weight(PASS ∪ FAIL ∪ ERROR)
//
// NOT_APPLICABLE results are excluded and ERROR counts as failing. The result
// is rounded half-up to one decimal place. The API computes the authoritative
// score; the agent uses the same formula for its local scan output.
package score

import "github.com/TahaUsama210/driftwatch/agent/internal/checks"

// Counts is the number of results with each status.
type Counts struct {
	Pass          int `json:"pass"`
	Fail          int `json:"fail"`
	Error         int `json:"error"`
	NotApplicable int `json:"notApplicable"`
}

// Tally counts results by status.
func Tally(results []checks.Result) Counts {
	var c Counts
	for _, r := range results {
		switch r.Status {
		case checks.StatusPass:
			c.Pass++
		case checks.StatusFail:
			c.Fail++
		case checks.StatusError:
			c.Error++
		case checks.StatusNotApplicable:
			c.NotApplicable++
		}
	}
	return c
}

// Compute returns the compliance score of results, or ok == false when no
// result is applicable.
func Compute(results []checks.Result) (score float64, ok bool) {
	var passed, applicable int
	for _, r := range results {
		w := r.Severity.Weight()
		switch r.Status {
		case checks.StatusPass:
			passed += w
			applicable += w
		case checks.StatusFail, checks.StatusError:
			applicable += w
		case checks.StatusNotApplicable:
		}
	}
	if applicable == 0 {
		return 0, false
	}
	// Round in integer arithmetic so that exact halves (e.g. 1/16 = 6.25%)
	// round up instead of falling victim to binary floating point.
	tenths := (2000*passed + applicable) / (2 * applicable)
	return float64(tenths) / 10, true
}
