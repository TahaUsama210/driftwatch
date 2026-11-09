package checks

import (
	"errors"
	"fmt"
	"io/fs"
	"strconv"
	"strings"
)

// sysctl passes when the kernel parameter name has the integer value want.
type sysctl struct {
	name string // dotted name, e.g. net.ipv4.ip_forward
	want int
}

func (s sysctl) evaluate(h *Host) Outcome {
	expected := strconv.Itoa(s.want)
	rel := "/sys/" + strings.ReplaceAll(s.name, ".", "/")
	display := "/proc" + rel // how the parameter is addressed on the host

	data, err := h.proc.ReadFile(rel)
	if err != nil {
		// The procfs mount point is a local detail, so report the host path.
		if pe, ok := errors.AsType[*fs.PathError](err); ok {
			err = fmt.Errorf("read %s: %w", display, pe.Err)
		}
		return errored(expected, err)
	}

	raw := strings.TrimSpace(string(data))
	evidence := fmt.Sprintf("%s: %s", display, raw)
	n, err := strconv.Atoi(raw)
	if err != nil {
		return errored(expected, fmt.Errorf("%s: not an integer", evidence))
	}
	return judge(n == s.want, expected, raw, evidence)
}
