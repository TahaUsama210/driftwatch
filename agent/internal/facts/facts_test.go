package facts_test

import (
	"errors"
	"io/fs"
	"runtime"
	"strings"
	"testing"

	"uuid"

	"github.com/TahaUsama210/driftwatch/agent/internal/facts"
	"github.com/TahaUsama210/driftwatch/agent/internal/hostfs/hostfstest"
)

func file(content string) hostfstest.File {
	return hostfstest.File{Data: []byte(content), Mode: 0o644}
}

var kernelProc = hostfstest.MapFS{"/sys/kernel/osrelease": file("6.6.87.2-microsoft-standard-WSL2\n")}

func TestCollectReadsHostFacts(t *testing.T) {
	root := hostfstest.MapFS{
		"/etc/hostname": file("# managed by cloud-init\n\nweb-1\n"),
		"/etc/os-release": file(`PRETTY_NAME="Ubuntu 24.04.3 LTS"
NAME="Ubuntu"
# a comment
VERSION_ID="24.04"
ID=ubuntu
`),
	}

	got, err := facts.Collector{Root: root, Proc: kernelProc}.Collect()
	if err != nil {
		t.Fatal(err)
	}

	want := facts.Host{
		Hostname: "web-1",
		OS:       facts.OS{ID: "ubuntu", Version: "24.04", PrettyName: "Ubuntu 24.04.3 LTS"},
		Kernel:   "6.6.87.2-microsoft-standard-WSL2",
		Arch:     runtime.GOARCH,
	}
	if got != want {
		t.Errorf("Collect = %+v, want %+v", got, want)
	}
}

func TestCollectPrefersTheHostnameFunction(t *testing.T) {
	root := hostfstest.MapFS{"/etc/hostname": file("from-file\n")}
	c := facts.Collector{Root: root, Proc: kernelProc, Hostname: func() (string, error) { return "from-kernel", nil }}

	got, err := c.Collect()
	if err != nil {
		t.Fatal(err)
	}
	if got.Hostname != "from-kernel" {
		t.Errorf("hostname = %q, want from-kernel", got.Hostname)
	}
}

func TestCollectParsesOSRelease(t *testing.T) {
	tests := []struct {
		name  string
		files hostfstest.MapFS
		want  facts.OS
	}{
		{
			name:  "single quotes and escapes",
			files: hostfstest.MapFS{"/etc/os-release": file("ID='rocky'\nPRETTY_NAME=\"Rocky \\\"Blue Onyx\\\" \\$9\"\nVERSION_ID=9.8\n")},
			want:  facts.OS{ID: "rocky", Version: "9.8", PrettyName: `Rocky "Blue Onyx" $9`},
		},
		{
			name:  "falls back to /usr/lib/os-release",
			files: hostfstest.MapFS{"/usr/lib/os-release": file("ID=alpine\nPRETTY_NAME=\"Alpine Linux v3.22\"\n")},
			want:  facts.OS{ID: "alpine", PrettyName: "Alpine Linux v3.22"},
		},
		{
			name:  "uses the os-release(5) defaults when absent",
			files: hostfstest.MapFS{},
			want:  facts.OS{ID: "linux", PrettyName: "Linux"},
		},
		{
			name:  "ignores lines that are not assignments",
			files: hostfstest.MapFS{"/etc/os-release": file("garbage\nID=debian\nX=\"\n")},
			want:  facts.OS{ID: "debian", PrettyName: "Linux"},
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			tt.files["/etc/hostname"] = file("h\n")
			got, err := facts.Collector{Root: tt.files, Proc: kernelProc}.Collect()
			if err != nil {
				t.Fatal(err)
			}
			if got.OS != tt.want {
				t.Errorf("OS = %+v, want %+v", got.OS, tt.want)
			}
		})
	}
}

func TestCollectFailsWithoutRequiredFacts(t *testing.T) {
	hostname := hostfstest.MapFS{"/etc/hostname": file("h\n")}
	tests := []struct {
		name    string
		c       facts.Collector
		wantErr string
	}{
		{"no /etc/hostname", facts.Collector{Root: hostfstest.MapFS{}, Proc: kernelProc}, "hostname"},
		{"empty /etc/hostname", facts.Collector{Root: hostfstest.MapFS{"/etc/hostname": file("\n# only a comment\n")}, Proc: kernelProc}, "contains no hostname"},
		{"hostname function fails", facts.Collector{Root: hostname, Proc: kernelProc, Hostname: func() (string, error) { return "", errors.New("uname failed") }}, "uname failed"},
		{"hostname function returns nothing", facts.Collector{Root: hostname, Proc: kernelProc, Hostname: func() (string, error) { return "", nil }}, "empty"},
		{"unreadable os-release", facts.Collector{Root: hostfstest.MapFS{"/etc/hostname": file("h"), "/etc/os-release": {Err: fs.ErrPermission}}, Proc: kernelProc}, "os-release"},
		{"no kernel release", facts.Collector{Root: hostname, Proc: hostfstest.MapFS{}}, "kernel release"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, err := tt.c.Collect()
			if err == nil || !strings.Contains(err.Error(), tt.wantErr) {
				t.Errorf("Collect error = %v, want it to contain %q", err, tt.wantErr)
			}
		})
	}
}

func TestMachineID(t *testing.T) {
	const (
		systemd  = "4c4c4544004a3510804bb2c04f4e4332"
		fallback = "7a0e8d4e-1c51-4a8f-9a59-0e1b2b4f1c3d"
	)
	tests := []struct {
		name     string
		root     hostfstest.MapFS
		fallback string
		want     string
	}{
		{"uses /etc/machine-id", hostfstest.MapFS{"/etc/machine-id": file(systemd + "\n")}, fallback, systemd},
		{"falls back when missing", hostfstest.MapFS{}, fallback, fallback},
		{"falls back when empty", hostfstest.MapFS{"/etc/machine-id": file("")}, fallback, fallback},
		{"falls back when uninitialized", hostfstest.MapFS{"/etc/machine-id": file("uninitialized\n")}, fallback, fallback},
		{"falls back when not lowercase hex", hostfstest.MapFS{"/etc/machine-id": file(strings.ToUpper(systemd))}, fallback, fallback},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, fresh, err := facts.MachineID(tt.root, tt.fallback)
			if err != nil {
				t.Fatal(err)
			}
			if got != tt.want || fresh {
				t.Errorf("MachineID = (%q, fresh %v), want (%q, false)", got, fresh, tt.want)
			}
		})
	}
}

func TestMachineIDGeneratesAUUIDWhenThereIsNoFallback(t *testing.T) {
	got, fresh, err := facts.MachineID(hostfstest.MapFS{}, "")
	if err != nil {
		t.Fatal(err)
	}
	id, err := uuid.Parse(got)
	if err != nil || !fresh {
		t.Fatalf("MachineID = (%q, fresh %v), want a fresh UUID: %v", got, fresh, err)
	}
	if version := id[6] >> 4; version != 4 {
		t.Errorf("generated UUID version = %d, want 4", version)
	}
}

func TestMachineIDDoesNotFallBackOnReadErrors(t *testing.T) {
	root := hostfstest.MapFS{"/etc/machine-id": {Err: fs.ErrPermission}}
	if _, _, err := facts.MachineID(root, "fallback-id"); !errors.Is(err, fs.ErrPermission) {
		t.Errorf("MachineID error = %v, want fs.ErrPermission", err)
	}
}
