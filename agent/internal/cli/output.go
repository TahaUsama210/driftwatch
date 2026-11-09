package cli

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"text/tabwriter"

	"github.com/TahaUsama210/driftwatch/agent/internal/checks"
	"github.com/TahaUsama210/driftwatch/agent/internal/scan"
	"github.com/TahaUsama210/driftwatch/agent/internal/score"
)

// printer writes formatted output and keeps the first write error, so that
// callers check for failure once instead of after every line.
type printer struct {
	w   io.Writer
	err error
}

func (p *printer) printf(format string, args ...any) {
	if p.err == nil {
		_, p.err = fmt.Fprintf(p.w, format, args...)
	}
}

func writeJSON(w io.Writer, v any) error {
	enc := json.NewEncoder(w)
	enc.SetIndent("", "  ")
	return enc.Encode(v)
}

func writeScanTable(w io.Writer, r *scan.Report) error {
	out := &printer{w: w}
	out.printf("Host: %s (%s, kernel %s, %s)\n\n", r.Host.Hostname, r.Host.OS.PrettyName, r.Host.Kernel, r.Host.Arch)

	tw := tabwriter.NewWriter(w, 0, 0, 2, ' ', 0)
	rows := &printer{w: tw}
	rows.printf("CHECK\tSEVERITY\tSTATUS\tACTUAL\tEXPECTED\n")
	for _, res := range r.Results {
		rows.printf("%s\t%s\t%s\t%s\t%s\n", res.CheckID, res.Severity, res.Status, dash(res.Actual), dash(res.Expected))
	}
	if err := errors.Join(out.err, rows.err, tw.Flush()); err != nil {
		return err
	}

	c := score.Tally(r.Results)
	summary := "n/a (no applicable checks)"
	if s, ok := score.Compute(r.Results); ok {
		summary = fmt.Sprintf("%.1f", s)
	}
	out.printf("\nScore: %s  (%d pass, %d fail, %d error, %d not applicable)\n",
		summary, c.Pass, c.Fail, c.Error, c.NotApplicable)
	return out.err
}

func writeCatalog(w io.Writer, catalog []checks.Check) error {
	tw := tabwriter.NewWriter(w, 0, 0, 2, ' ', 0)
	rows := &printer{w: tw}
	rows.printf("ID\tCATEGORY\tSEVERITY\tTITLE\n")
	for _, c := range catalog {
		rows.printf("%s\t%s\t%s\t%s\n", c.ID, c.Category, c.Severity, c.Title)
	}
	return errors.Join(rows.err, tw.Flush())
}

func dash(s string) string {
	if s == "" {
		return "-"
	}
	return s
}
