package checks_test

import (
	"io/fs"
	"testing"

	"github.com/TahaUsama210/driftwatch/agent/internal/checks"
	"github.com/TahaUsama210/driftwatch/agent/internal/hostfs/hostfstest"
)

func TestKernelParameterChecks(t *testing.T) {
	tests := []struct {
		id                      string
		file                    string // under the procfs root
		compliant, noncompliant string
	}{
		{"network.ip_forwarding_disabled", "/sys/net/ipv4/ip_forward", "0", "1"},
		{"network.icmp_redirects_rejected", "/sys/net/ipv4/conf/all/accept_redirects", "0", "1"},
		{"network.source_routing_rejected", "/sys/net/ipv4/conf/all/accept_source_route", "0", "1"},
		{"network.syn_cookies_enabled", "/sys/net/ipv4/tcp_syncookies", "1", "0"},
		{"kernel.aslr_enabled", "/sys/kernel/randomize_va_space", "2", "1"},
	}
	for _, tt := range tests {
		t.Run(tt.id, func(t *testing.T) {
			proc := func(value string) hostfstest.MapFS {
				return hostfstest.MapFS{tt.file: text(value+"\n", 0o644)}
			}
			want{status: checks.StatusPass, actual: tt.compliant, expected: tt.compliant, evidence: "/proc" + tt.file + ": " + tt.compliant}.
				verify(t, runCheck(t, tt.id, nil, proc(tt.compliant)))
			want{status: checks.StatusFail, actual: tt.noncompliant, expected: tt.compliant}.
				verify(t, runCheck(t, tt.id, nil, proc(tt.noncompliant)))
			want{status: checks.StatusError, evidence: "not an integer"}.
				verify(t, runCheck(t, tt.id, nil, proc("enabled")))
		})
	}
}

func TestKernelParameterChecksErrorWhenUnreadable(t *testing.T) {
	tests := []struct {
		name string
		proc hostfstest.MapFS
		want string
	}{
		{"missing", hostfstest.MapFS{}, "read /proc/sys/net/ipv4/ip_forward: file does not exist"},
		{"permission denied", hostfstest.MapFS{"/sys/net/ipv4/ip_forward": {Err: fs.ErrPermission}}, "read /proc/sys/net/ipv4/ip_forward: permission denied"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			want{status: checks.StatusError, expected: "0", evidence: tt.want}.
				verify(t, runCheck(t, "network.ip_forwarding_disabled", nil, tt.proc))
		})
	}
}
