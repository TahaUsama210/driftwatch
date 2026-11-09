package score_test

import (
	"testing"

	"github.com/TahaUsama210/driftwatch/agent/internal/checks"
	"github.com/TahaUsama210/driftwatch/agent/internal/score"
)

func result(status checks.Status, severity checks.Severity) checks.Result {
	return checks.Result{Status: status, Severity: severity}
}

// repeat returns n copies of r.
func repeat(n int, r checks.Result) []checks.Result {
	out := make([]checks.Result, n)
	for i := range out {
		out[i] = r
	}
	return out
}

func TestCompute(t *testing.T) {
	const (
		pass = checks.StatusPass
		fail = checks.StatusFail
		erro = checks.StatusError
		na   = checks.StatusNotApplicable
	)
	tests := []struct {
		name    string
		results []checks.Result
		want    float64
		wantOK  bool
	}{
		{"everything passes", []checks.Result{result(pass, "high"), result(pass, "low")}, 100, true},
		{"everything fails", []checks.Result{result(fail, "critical")}, 0, true},
		{"weights by severity", []checks.Result{result(pass, "critical"), result(fail, "low")}, 83.3, true}, // 5/6
		{"not applicable is excluded", []checks.Result{result(pass, "medium"), result(na, "critical")}, 100, true},
		{"error counts as failing", []checks.Result{result(pass, "high"), result(erro, "high")}, 50, true},
		{"two thirds rounds up", []checks.Result{result(pass, "medium"), result(fail, "low")}, 66.7, true},
		{"one third rounds down", []checks.Result{result(pass, "low"), result(fail, "medium")}, 33.3, true},
		// 1/16 = 6.25%: exactly half a tenth, which must round up.
		{"exact half rounds up", append([]checks.Result{result(pass, "low")}, repeat(15, result(fail, "low"))...), 6.3, true},
		{"nothing applicable", []checks.Result{result(na, "high")}, 0, false},
		{"no results", nil, 0, false},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, ok := score.Compute(tt.results)
			if got != tt.want || ok != tt.wantOK {
				t.Errorf("Compute = (%v, %v), want (%v, %v)", got, ok, tt.want, tt.wantOK)
			}
		})
	}
}

func TestTally(t *testing.T) {
	results := []checks.Result{
		result(checks.StatusPass, "low"),
		result(checks.StatusPass, "high"),
		result(checks.StatusFail, "low"),
		result(checks.StatusError, "low"),
		result(checks.StatusNotApplicable, "low"),
		result(checks.StatusNotApplicable, "low"),
	}
	want := score.Counts{Pass: 2, Fail: 1, Error: 1, NotApplicable: 2}
	if got := score.Tally(results); got != want {
		t.Errorf("Tally = %+v, want %+v", got, want)
	}
}
