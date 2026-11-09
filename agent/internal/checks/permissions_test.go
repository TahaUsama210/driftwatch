package checks_test

import (
	"io/fs"
	"testing"

	"github.com/TahaUsama210/driftwatch/agent/internal/checks"
	"github.com/TahaUsama210/driftwatch/agent/internal/hostfs/hostfstest"
)

func TestFilePermissionChecks(t *testing.T) {
	pass, fail, errored, na := checks.StatusPass, checks.StatusFail, checks.StatusError, checks.StatusNotApplicable
	owned := func(mode fs.FileMode, uid uint32) *hostfstest.File {
		return &hostfstest.File{Mode: mode, UID: uid, GID: 42}
	}
	tests := []struct {
		name string
		id   string
		path string
		file *hostfstest.File // nil means the file does not exist
		want want
	}{
		{"private sshd_config", "ssh.config_permissions", sshdConfig, owned(0o600, 0),
			want{status: pass, actual: "owner uid 0, mode 0600", expected: "owner uid 0, mode 0700 or stricter", evidence: sshdConfig + ": uid 0, gid 42, mode 0600"}},
		{"world-readable sshd_config", "ssh.config_permissions", sshdConfig, owned(0o644, 0),
			want{status: fail, actual: "owner uid 0, mode 0644", evidence: "permission bits 0044 must not be set"}},
		{"sshd_config owned by a user", "ssh.config_permissions", sshdConfig, owned(0o600, 1000),
			want{status: fail, actual: "owner uid 1000, mode 0600", evidence: "owned by uid 1000 instead of 0"}},
		{"missing sshd_config", "ssh.config_permissions", sshdConfig, nil,
			want{status: na, evidence: "SSH server is not installed"}},

		{"Debian-style shadow", "accounts.shadow_permissions", "/etc/shadow", owned(0o640, 0),
			want{status: pass, expected: "owner uid 0, mode 0640 or stricter"}},
		{"RHEL-style shadow", "accounts.shadow_permissions", "/etc/shadow", owned(0o000, 0),
			want{status: pass, actual: "owner uid 0, mode 0000"}},
		{"world-readable shadow", "accounts.shadow_permissions", "/etc/shadow", owned(0o644, 0),
			want{status: fail, evidence: "permission bits 0004 must not be set"}},
		{"group-writable shadow owned by a user", "accounts.shadow_permissions", "/etc/shadow", owned(0o660, 1000),
			want{status: fail, evidence: "owned by uid 1000 instead of 0; permission bits 0020 must not be set"}},
		{"missing shadow", "accounts.shadow_permissions", "/etc/shadow", nil,
			want{status: errored, evidence: "/etc/shadow: file does not exist"}},

		{"standard passwd", "accounts.passwd_permissions", "/etc/passwd", owned(0o644, 0),
			want{status: pass, expected: "owner uid 0, mode 0644 or stricter"}},
		{"group-writable passwd", "accounts.passwd_permissions", "/etc/passwd", owned(0o664, 0),
			want{status: fail, evidence: "permission bits 0020 must not be set"}},
		{"missing passwd", "accounts.passwd_permissions", "/etc/passwd", nil,
			want{status: errored}},

		{"private crontab", "filesystem.crontab_permissions", "/etc/crontab", owned(0o600, 0),
			want{status: pass, expected: "owner uid 0, mode 0700 or stricter"}},
		{"default crontab", "filesystem.crontab_permissions", "/etc/crontab", owned(0o644, 0),
			want{status: fail, actual: "owner uid 0, mode 0644"}},
		{"no crontab", "filesystem.crontab_permissions", "/etc/crontab", nil,
			want{status: na, evidence: "/etc/crontab not found"}},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			root := hostfstest.MapFS{}
			if tt.file != nil {
				root[tt.path] = *tt.file
			}
			tt.want.verify(t, runCheck(t, tt.id, root, nil))
		})
	}
}

func TestFilePermissionChecksErrorWhenStatFails(t *testing.T) {
	for id, path := range map[string]string{
		"ssh.config_permissions":         sshdConfig,
		"accounts.shadow_permissions":    "/etc/shadow",
		"accounts.passwd_permissions":    "/etc/passwd",
		"filesystem.crontab_permissions": "/etc/crontab",
	} {
		t.Run(id, func(t *testing.T) {
			root := hostfstest.MapFS{path: {Err: fs.ErrPermission}}
			want{status: checks.StatusError, evidence: path + ": permission denied"}.verify(t, runCheck(t, id, root, nil))
		})
	}
}
