package checks_test

import (
	"io/fs"
	"strings"
	"testing"

	"github.com/TahaUsama210/driftwatch/agent/internal/checks"
	"github.com/TahaUsama210/driftwatch/agent/internal/hostfs/hostfstest"
)

const rootEntry = "root:x:0:0:root:/root:/bin/bash\n"

func TestUID0OnlyRoot(t *testing.T) {
	tests := []struct {
		name   string
		passwd string
		want   want
	}{
		{"only root", rootEntry + "daemon:x:1:1:daemon:/usr/sbin:/usr/sbin/nologin\n",
			want{status: checks.StatusPass, actual: "root", expected: "root", evidence: "UID 0 accounts: root (/etc/passwd:1)"}},
		{"an extra UID 0 account", rootEntry + "toor:x:0:0:legacy admin:/root:/bin/bash\n",
			want{status: checks.StatusFail, actual: "root, toor", evidence: "toor (/etc/passwd:2)"}},
		{"UID 0 under another name", "admin:x:0:0::/root:/bin/sh\n",
			want{status: checks.StatusFail, actual: "admin"}},
		{"no UID 0 account", "daemon:x:1:1::/:/bin/false\n",
			want{status: checks.StatusFail, actual: "none", evidence: "no account has UID 0"}},
		{"blank lines, comments and NIS entries are skipped", "\n# local accounts\n" + rootEntry + "+::::::\n-@ops::::::\n",
			want{status: checks.StatusPass, actual: "root"}},
		{"a malformed entry", rootEntry + "broken:x:0\n",
			want{status: checks.StatusError, expected: "root", evidence: "/etc/passwd:2: malformed entry: 3 fields, want at least 7"}},
		{"a non-numeric UID", rootEntry + "odd:x:zero:0::/:/bin/sh\n",
			want{status: checks.StatusError, evidence: `/etc/passwd:2: invalid UID "zero"`}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			root := hostfstest.MapFS{"/etc/passwd": text(tt.passwd, 0o644)}
			tt.want.verify(t, runCheck(t, "accounts.uid0_only_root", root, nil))
		})
	}
}

func TestUID0OnlyRootErrorsWithoutPasswd(t *testing.T) {
	want{status: checks.StatusError, evidence: "/etc/passwd"}.
		verify(t, runCheck(t, "accounts.uid0_only_root", hostfstest.MapFS{}, nil))
}

func TestNoEmptyPasswords(t *testing.T) {
	const hash = "$6$rounds=5000$saltsalt$hashhashhash"
	tests := []struct {
		name   string
		shadow string
		want   want
	}{
		{"every account has a password or is locked", "root:" + hash + ":19000:0:99999:7:::\ndaemon:*:19000:0:99999:7:::\nsvc:!:19000::::::\n",
			want{status: checks.StatusPass, actual: "none", expected: "none", evidence: "3 accounts"}},
		{"an account with an empty password", "root:" + hash + ":19000:0:99999:7:::\nguest::19000:0:99999:7:::\n",
			want{status: checks.StatusFail, actual: "guest", evidence: "/etc/shadow:2: guest"}},
		{"a malformed entry", "root\n",
			want{status: checks.StatusError, evidence: "/etc/shadow:1: malformed entry"}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			root := hostfstest.MapFS{"/etc/shadow": text(tt.shadow, 0o640)}
			got := runCheck(t, "accounts.no_empty_passwords", root, nil)
			tt.want.verify(t, got)
			if strings.Contains(got.Evidence+got.Actual, hash) {
				t.Errorf("result leaks a password hash: %+v", got)
			}
		})
	}
}

func TestNoEmptyPasswordsErrorsWhenShadowIsUnreadable(t *testing.T) {
	root := hostfstest.MapFS{"/etc/shadow": {Err: fs.ErrPermission}}
	want{status: checks.StatusError, evidence: "/etc/shadow: permission denied"}.
		verify(t, runCheck(t, "accounts.no_empty_passwords", root, nil))
}

func TestPasswordAgingChecks(t *testing.T) {
	pass, fail, errored := checks.StatusPass, checks.StatusFail, checks.StatusError
	tests := []struct {
		id        string
		loginDefs string
		want      want
	}{
		{"accounts.pass_max_days", "PASS_MAX_DAYS\t90\n", want{status: pass, actual: "90", expected: "between 1 and 365", evidence: "/etc/login.defs:1: PASS_MAX_DAYS 90"}},
		{"accounts.pass_max_days", "PASS_MAX_DAYS 365\n", want{status: pass}},
		{"accounts.pass_max_days", "PASS_MAX_DAYS 99999\n", want{status: fail, actual: "99999"}},
		{"accounts.pass_max_days", "PASS_MAX_DAYS 0\n", want{status: fail}},
		{"accounts.pass_max_days", "PASS_MAX_DAYS -1\n", want{status: fail}},
		{"accounts.pass_max_days", "PASS_MAX_DAYS 99999\n# tightened\nPASS_MAX_DAYS 60\n", want{status: pass, evidence: "/etc/login.defs:3:"}},
		{"accounts.pass_max_days", "#PASS_MAX_DAYS 90\nPASS_MIN_DAYS 1\n", want{status: fail, actual: "not set", evidence: "PASS_MAX_DAYS is not set in /etc/login.defs"}},
		{"accounts.pass_max_days", "pass_max_days 90\n", want{status: fail, actual: "not set"}},
		{"accounts.pass_max_days", "PASS_MAX_DAYS ninety\n", want{status: errored, evidence: "PASS_MAX_DAYS ninety: not an integer"}},
		// shadow-utils ignores a key without a value and has no trailing
		// comments, so the value below is "90 # policy" and is unusable.
		{"accounts.pass_max_days", "PASS_MAX_DAYS\n", want{status: fail, actual: "not set"}},
		{"accounts.pass_max_days", "PASS_MAX_DAYS 90 # policy\n", want{status: errored, evidence: "PASS_MAX_DAYS 90 # policy: not an integer"}},
		{"accounts.pass_max_days", "PASS_MAX_DAYS \"90\"\n", want{status: pass, actual: "90"}},
		{"accounts.pass_max_days", "  PASS_MAX_DAYS 90  \r\n", want{status: pass, actual: "90"}},

		{"accounts.pass_min_days", "PASS_MIN_DAYS 1\n", want{status: pass, actual: "1", expected: "at least 1"}},
		{"accounts.pass_min_days", "PASS_MIN_DAYS 0\n", want{status: fail, actual: "0"}},

		{"accounts.pass_warn_age", "PASS_WARN_AGE 14\n", want{status: pass, expected: "at least 7"}},
		{"accounts.pass_warn_age", "PASS_WARN_AGE 7\n", want{status: pass}},
		{"accounts.pass_warn_age", "PASS_WARN_AGE 6\n", want{status: fail, actual: "6"}},
	}
	for _, tt := range tests {
		t.Run(tt.id+"/"+strings.ReplaceAll(tt.loginDefs, "\n", `\n`), func(t *testing.T) {
			root := hostfstest.MapFS{"/etc/login.defs": text(tt.loginDefs, 0o644)}
			tt.want.verify(t, runCheck(t, tt.id, root, nil))
		})
	}
}

func TestPasswordAgingChecksFailWithoutLoginDefs(t *testing.T) {
	for _, id := range []string{"accounts.pass_max_days", "accounts.pass_min_days", "accounts.pass_warn_age"} {
		t.Run(id, func(t *testing.T) {
			want{status: checks.StatusFail, actual: "not set", evidence: "/etc/login.defs not found"}.
				verify(t, runCheck(t, id, hostfstest.MapFS{}, nil))
		})
	}
}

func TestPasswordAgingChecksErrorWhenLoginDefsIsUnreadable(t *testing.T) {
	root := hostfstest.MapFS{"/etc/login.defs": {Err: fs.ErrPermission}}
	want{status: checks.StatusError, evidence: "permission denied"}.
		verify(t, runCheck(t, "accounts.pass_max_days", root, nil))
}
