package checks_test

import (
	"io/fs"
	"strings"
	"testing"

	"github.com/TahaUsama210/driftwatch/agent/internal/checks"
	"github.com/TahaUsama210/driftwatch/agent/internal/hostfs/hostfstest"
)

const sshdConfig = "/etc/ssh/sshd_config"

func TestSSHOptionChecks(t *testing.T) {
	pass, fail, errored := checks.StatusPass, checks.StatusFail, checks.StatusError
	tests := []struct {
		id     string
		config string
		want   want
	}{
		{"ssh.permit_root_login", "PermitRootLogin no", want{status: pass, actual: "no", expected: "no", evidence: sshdConfig + ":1: PermitRootLogin no"}},
		{"ssh.permit_root_login", "PermitRootLogin NO", want{status: pass, actual: "no"}},
		{"ssh.permit_root_login", "PermitRootLogin yes", want{status: fail, actual: "yes"}},
		{"ssh.permit_root_login", "PermitRootLogin without-password", want{status: fail, actual: "without-password"}},
		{"ssh.permit_root_login", "Port 22", want{status: fail, actual: "prohibit-password", evidence: "PermitRootLogin is not set; OpenSSH default is prohibit-password"}},
		{"ssh.permit_root_login", "PermitRootLogin maybe", want{status: errored, expected: "no", evidence: `PermitRootLogin maybe: unsupported value "maybe"`}},
		{"ssh.permit_root_login", "PermitRootLogin no yes", want{status: errored, evidence: "takes exactly one argument"}},

		{"ssh.password_authentication", "PasswordAuthentication no", want{status: pass, actual: "no"}},
		{"ssh.password_authentication", "PasswordAuthentication yes", want{status: fail, actual: "yes"}},
		{"ssh.password_authentication", "Port 22", want{status: fail, actual: "yes", evidence: "OpenSSH default is yes"}},

		{"ssh.permit_empty_passwords", "Port 22", want{status: pass, actual: "no", evidence: "OpenSSH default is no"}},
		{"ssh.permit_empty_passwords", "PermitEmptyPasswords yes", want{status: fail, actual: "yes"}},
		{"ssh.permit_empty_passwords", "PermitEmptyPasswords on", want{status: errored, evidence: `unsupported value "on"`}},

		{"ssh.max_auth_tries", "MaxAuthTries 4", want{status: pass, actual: "4", expected: "at most 4"}},
		{"ssh.max_auth_tries", "MaxAuthTries 3", want{status: pass, actual: "3"}},
		{"ssh.max_auth_tries", "MaxAuthTries 5", want{status: fail, actual: "5"}},
		{"ssh.max_auth_tries", "Port 22", want{status: fail, actual: "6", evidence: "OpenSSH default is 6"}},
		{"ssh.max_auth_tries", "MaxAuthTries three", want{status: errored, evidence: `invalid integer "three"`}},
		{"ssh.max_auth_tries", "MaxAuthTries -1", want{status: errored}},

		{"ssh.x11_forwarding", "Port 22", want{status: pass, actual: "no"}},
		{"ssh.x11_forwarding", "X11Forwarding yes", want{status: fail, actual: "yes"}},

		{"ssh.login_grace_time", "LoginGraceTime 60", want{status: pass, actual: "60 seconds", expected: "between 1 and 60 seconds"}},
		{"ssh.login_grace_time", "LoginGraceTime 1m", want{status: pass, actual: "60 seconds", evidence: "LoginGraceTime 1m"}},
		{"ssh.login_grace_time", "LoginGraceTime 2m", want{status: fail, actual: "120 seconds"}},
		{"ssh.login_grace_time", "LoginGraceTime 0", want{status: fail, actual: "0 seconds"}},
		{"ssh.login_grace_time", "Port 22", want{status: fail, actual: "120 seconds", evidence: "OpenSSH default is 120"}},
		{"ssh.login_grace_time", "LoginGraceTime soon", want{status: errored, evidence: `invalid time value "soon"`}},

		{"ssh.idle_timeout", "ClientAliveInterval 300", want{status: pass, actual: "300 seconds", expected: "between 1 and 900 seconds"}},
		{"ssh.idle_timeout", "ClientAliveInterval 15m", want{status: pass, actual: "900 seconds"}},
		{"ssh.idle_timeout", "ClientAliveInterval 16m", want{status: fail, actual: "960 seconds"}},
		{"ssh.idle_timeout", "Port 22", want{status: fail, actual: "0 seconds", evidence: "OpenSSH default is 0"}},
	}
	for _, tt := range tests {
		t.Run(tt.id+"/"+tt.config, func(t *testing.T) {
			root := hostfstest.MapFS{sshdConfig: text(tt.config+"\n", 0o600)}
			tt.want.verify(t, runCheck(t, tt.id, root, nil))
		})
	}
}

func TestSSHChecksUseTheIncludedValue(t *testing.T) {
	root := hostfstest.MapFS{
		sshdConfig: text("Include /etc/ssh/sshd_config.d/*.conf\nPermitRootLogin yes\n", 0o600),
		"/etc/ssh/sshd_config.d/10-hardening.conf": text("PermitRootLogin no\n", 0o600),
	}
	want{status: checks.StatusPass, actual: "no", evidence: "/etc/ssh/sshd_config.d/10-hardening.conf:1: PermitRootLogin no"}.
		verify(t, runCheck(t, "ssh.permit_root_login", root, nil))
}

func TestSSHChecksMentionUnevaluatedMatchOverrides(t *testing.T) {
	root := hostfstest.MapFS{sshdConfig: text("PermitRootLogin no\nMatch User backup\n  PermitRootLogin yes\n", 0o600)}

	want{status: checks.StatusPass, evidence: "1 Match block override(s) not evaluated, first at /etc/ssh/sshd_config:3"}.
		verify(t, runCheck(t, "ssh.permit_root_login", root, nil))
}

func sshCheckIDs() []string {
	var ids []string
	for _, c := range checks.Catalog() {
		if c.Category == checks.CategorySSH {
			ids = append(ids, c.ID)
		}
	}
	return ids
}

func TestSSHChecksAreNotApplicableWithoutSSHServer(t *testing.T) {
	for _, id := range sshCheckIDs() {
		t.Run(id, func(t *testing.T) {
			want{status: checks.StatusNotApplicable, evidence: sshdConfig + " not found"}.
				verify(t, runCheck(t, id, hostfstest.MapFS{}, nil))
		})
	}
}

func TestSSHChecksErrorOnUnreadableConfig(t *testing.T) {
	root := hostfstest.MapFS{sshdConfig: {Err: fs.ErrPermission}}
	for _, id := range sshCheckIDs() {
		t.Run(id, func(t *testing.T) {
			want{status: checks.StatusError, evidence: "permission denied"}.verify(t, runCheck(t, id, root, nil))
		})
	}
}

func TestSSHOptionChecksErrorOnInvalidConfig(t *testing.T) {
	root := hostfstest.MapFS{sshdConfig: text("Port 22\nPermitRootLogin\n", 0o600)}
	for _, id := range sshCheckIDs() {
		if id == "ssh.config_permissions" {
			continue // a syntax error does not change the file's permissions
		}
		t.Run(id, func(t *testing.T) {
			got := runCheck(t, id, root, nil)
			want{status: checks.StatusError, evidence: sshdConfig + ":2: no argument after keyword"}.verify(t, got)
			if got.Expected == "" || strings.Contains(got.Expected, "%") {
				t.Errorf("expected = %q, want the compliant value even on error", got.Expected)
			}
		})
	}
}
