package checks

import (
	"errors"
	"fmt"
	"io/fs"
	"slices"
	"strconv"
	"strings"

	"github.com/TahaUsama210/driftwatch/agent/internal/sshd"
)

// sshOption evaluates one sshd_config keyword.
type sshOption struct {
	keyword  string
	fallback string // OpenSSH's default when the keyword is unset
	expected string
	// assess interprets the effective value. It returns an error for values
	// sshd itself would reject, since the effective setting is then unknown.
	assess func(value string) (actual string, ok bool, err error)
}

func (o sshOption) evaluate(h *Host) Outcome {
	cfg, err := h.sshdConfig()
	if errors.Is(err, fs.ErrNotExist) {
		return sshNotInstalled()
	}
	if err != nil {
		return errored(o.expected, err)
	}

	value := o.fallback
	evidence := fmt.Sprintf("%s is not set; OpenSSH default is %s", o.keyword, o.fallback)
	if d, ok := cfg.Lookup(o.keyword); ok {
		if len(d.Args) != 1 {
			return errored(o.expected, fmt.Errorf("%s: %s takes exactly one argument", d, o.keyword))
		}
		value, evidence = d.Args[0], d.String()
	}

	actual, ok, err := o.assess(value)
	if err != nil {
		return errored(o.expected, fmt.Errorf("%s: %w", evidence, err))
	}
	if overrides := cfg.Conditional(o.keyword); len(overrides) > 0 {
		first := overrides[0]
		evidence += fmt.Sprintf("; %d Match block override(s) not evaluated, first at %s:%d",
			len(overrides), first.File, first.Line)
	}
	return judge(ok, o.expected, actual, evidence)
}

func sshNotInstalled() Outcome {
	return notApplicable(sshd.DefaultPath + " not found; the SSH server is not installed")
}

// mustBeNo accepts the values in allowed (case-insensitively, as sshd does)
// and passes only for "no".
func mustBeNo(allowed ...string) func(string) (string, bool, error) {
	return func(value string) (string, bool, error) {
		v := strings.ToLower(value)
		if !slices.Contains(allowed, v) {
			return "", false, fmt.Errorf("unsupported value %q", value)
		}
		return v, v == "no", nil
	}
}

func atMost(limit int) func(string) (string, bool, error) {
	return func(value string) (string, bool, error) {
		n, err := strconv.Atoi(value)
		if err != nil || n < 0 {
			return "", false, fmt.Errorf("invalid integer %q", value)
		}
		return value, n <= limit, nil
	}
}

func secondsBetween(lo, hi int) func(string) (string, bool, error) {
	return func(value string) (string, bool, error) {
		secs, err := sshd.ParseSeconds(value)
		if err != nil {
			return "", false, err
		}
		return fmt.Sprintf("%d seconds", secs), secs >= lo && secs <= hi, nil
	}
}

var (
	yesNo = []string{"yes", "no"}

	permitRootLogin = sshOption{
		keyword:  "PermitRootLogin",
		fallback: "prohibit-password",
		expected: "no",
		assess: mustBeNo(
			"yes", "prohibit-password", "without-password", "forced-commands-only", "no"),
	}
	passwordAuthentication = sshOption{
		keyword:  "PasswordAuthentication",
		fallback: "yes",
		expected: "no",
		assess:   mustBeNo(yesNo...),
	}
	permitEmptyPasswords = sshOption{
		keyword:  "PermitEmptyPasswords",
		fallback: "no",
		expected: "no",
		assess:   mustBeNo(yesNo...),
	}
	maxAuthTries = sshOption{
		keyword:  "MaxAuthTries",
		fallback: "6",
		expected: "at most 4",
		assess:   atMost(4),
	}
	x11Forwarding = sshOption{
		keyword:  "X11Forwarding",
		fallback: "no",
		expected: "no",
		assess:   mustBeNo(yesNo...),
	}
	loginGraceTime = sshOption{
		keyword:  "LoginGraceTime",
		fallback: "120",
		expected: "between 1 and 60 seconds",
		assess:   secondsBetween(1, 60),
	}
	clientAliveInterval = sshOption{
		keyword:  "ClientAliveInterval",
		fallback: "0",
		expected: "between 1 and 900 seconds",
		assess:   secondsBetween(1, 900),
	}
)

// sshConfigPermissions checks the main sshd_config file only; included files
// are not inspected.
func sshConfigPermissions(h *Host) Outcome {
	return filePermissions{path: sshd.DefaultPath, forbidden: 0o077, missing: sshNotInstalled}.evaluate(h)
}
