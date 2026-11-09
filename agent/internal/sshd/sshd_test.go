package sshd_test

import (
	"errors"
	"fmt"
	"io/fs"
	"slices"
	"strings"
	"testing"

	"github.com/TahaUsama210/driftwatch/agent/internal/hostfs/hostfstest"
	"github.com/TahaUsama210/driftwatch/agent/internal/sshd"
)

func files(m map[string]string) hostfstest.MapFS {
	fsys := make(hostfstest.MapFS, len(m))
	for name, content := range m {
		fsys[name] = hostfstest.File{Data: []byte(content), Mode: 0o600}
	}
	return fsys
}

func load(t *testing.T, fsys hostfstest.MapFS) *sshd.Config {
	t.Helper()
	cfg, err := sshd.Load(fsys, sshd.DefaultPath)
	if err != nil {
		t.Fatalf("Load: %v", err)
	}
	return cfg
}

// effective renders the deciding directive for keyword as "file:line value".
func effective(cfg *sshd.Config, keyword string) string {
	d, ok := cfg.Lookup(keyword)
	if !ok {
		return "unset"
	}
	return fmt.Sprintf("%s:%d %s", d.File, d.Line, d.Value())
}

func TestLoadEvaluatesDirectives(t *testing.T) {
	const main = sshd.DefaultPath
	tests := []struct {
		name    string
		files   map[string]string
		keyword string
		want    string
	}{
		{
			name:    "comments and blank lines are skipped",
			files:   map[string]string{main: "# PermitRootLogin yes\n\n   # indented comment\nPermitRootLogin no\n"},
			keyword: "PermitRootLogin",
			want:    main + ":4 no",
		},
		{
			name:    "keywords are case-insensitive",
			files:   map[string]string{main: "permitrootlogin no\n"},
			keyword: "PERMITROOTLOGIN",
			want:    main + ":1 no",
		},
		{
			name:    "the first occurrence wins",
			files:   map[string]string{main: "PermitRootLogin no\nPermitRootLogin yes\n"},
			keyword: "PermitRootLogin",
			want:    main + ":1 no",
		},
		{
			name:    "an unset keyword is reported as unset",
			files:   map[string]string{main: "Port 22\n"},
			keyword: "PermitRootLogin",
			want:    "unset",
		},
		{
			name:    "equals sign separates keyword and value",
			files:   map[string]string{main: "MaxAuthTries=3\n"},
			keyword: "MaxAuthTries",
			want:    main + ":1 3",
		},
		{
			name:    "equals sign surrounded by spaces",
			files:   map[string]string{main: "MaxAuthTries = 3\n"},
			keyword: "MaxAuthTries",
			want:    main + ":1 3",
		},
		{
			name:    "trailing comments are stripped",
			files:   map[string]string{main: "X11Forwarding no # hardened\n"},
			keyword: "X11Forwarding",
			want:    main + ":1 no",
		},
		{
			name:    "quoted arguments keep their spaces",
			files:   map[string]string{main: `Banner "/etc/issue net"` + "\n"},
			keyword: "Banner",
			want:    main + ":1 /etc/issue net",
		},
		{
			name:    "CRLF line endings are tolerated",
			files:   map[string]string{main: "PermitRootLogin no\r\nX11Forwarding yes\r\n"},
			keyword: "PermitRootLogin",
			want:    main + ":1 no",
		},
		{
			name: "an Include before a directive takes precedence over it",
			files: map[string]string{
				main: "Include /etc/ssh/sshd_config.d/*.conf\nX11Forwarding yes\n",
				"/etc/ssh/sshd_config.d/10-hardening.conf": "X11Forwarding no\n",
			},
			keyword: "X11Forwarding",
			want:    "/etc/ssh/sshd_config.d/10-hardening.conf:1 no",
		},
		{
			name: "a directive before an Include takes precedence over it",
			files: map[string]string{
				main:                            "PermitRootLogin yes\nInclude sshd_config.d/*.conf\n",
				"/etc/ssh/sshd_config.d/a.conf": "PermitRootLogin no\n",
			},
			keyword: "PermitRootLogin",
			want:    main + ":1 yes",
		},
		{
			name: "relative Include paths resolve against /etc/ssh",
			files: map[string]string{
				main:                  "Include extra.conf\n",
				"/etc/ssh/extra.conf": "MaxAuthTries 2\n",
			},
			keyword: "MaxAuthTries",
			want:    "/etc/ssh/extra.conf:1 2",
		},
		{
			name: "globbed files are read in lexical order",
			files: map[string]string{
				main:                               "Include /etc/ssh/sshd_config.d/*.conf\n",
				"/etc/ssh/sshd_config.d/50-b.conf": "LoginGraceTime 90\n",
				"/etc/ssh/sshd_config.d/10-a.conf": "LoginGraceTime 30\n",
			},
			keyword: "LoginGraceTime",
			want:    "/etc/ssh/sshd_config.d/10-a.conf:1 30",
		},
		{
			name: "files that do not match the glob are ignored",
			files: map[string]string{
				main:                           "Include /etc/ssh/sshd_config.d/*.conf\n",
				"/etc/ssh/sshd_config.d/a.bak": "PermitRootLogin yes\n",
			},
			keyword: "PermitRootLogin",
			want:    "unset",
		},
		{
			name: "an Include with several patterns expands each in turn",
			files: map[string]string{
				main:              "Include b.conf a.conf\n",
				"/etc/ssh/a.conf": "ClientAliveInterval 100\n",
				"/etc/ssh/b.conf": "ClientAliveInterval 200\n",
			},
			keyword: "ClientAliveInterval",
			want:    "/etc/ssh/b.conf:1 200",
		},
		{
			name:    "an Include that matches nothing is not an error",
			files:   map[string]string{main: "Include /etc/ssh/sshd_config.d/*.conf\nPermitRootLogin no\n"},
			keyword: "PermitRootLogin",
			want:    main + ":2 no",
		},
		{
			name: "nested Includes are expanded",
			files: map[string]string{
				main:              "Include a.conf\n",
				"/etc/ssh/a.conf": "Include b.conf\n",
				"/etc/ssh/b.conf": "PasswordAuthentication no\n",
			},
			keyword: "PasswordAuthentication",
			want:    "/etc/ssh/b.conf:1 no",
		},
		{
			name:    "directives inside a Match block are not applied",
			files:   map[string]string{main: "Match User admin\n  PermitRootLogin yes\n"},
			keyword: "PermitRootLogin",
			want:    "unset",
		},
		{
			name:    "Match all ends a Match block",
			files:   map[string]string{main: "Match User admin\n  PermitRootLogin yes\nMatch all\nPermitRootLogin no\n"},
			keyword: "PermitRootLogin",
			want:    main + ":4 no",
		},
		{
			name:    "Match all is case-insensitive and may carry a comment",
			files:   map[string]string{main: "Match Address 10.0.0.0/8\n  X11Forwarding yes\nmatch ALL # global again\nX11Forwarding no\n"},
			keyword: "X11Forwarding",
			want:    main + ":4 no",
		},
		{
			name:    "Match with all and another criterion is still conditional",
			files:   map[string]string{main: "Match all User admin\n  PermitRootLogin yes\n"},
			keyword: "PermitRootLogin",
			want:    "unset",
		},
		{
			name: "a Match block in an included file ends with that file",
			files: map[string]string{
				main:              "Include a.conf\nPermitRootLogin no\n",
				"/etc/ssh/a.conf": "Match Address 10.0.0.0/8\n  PermitRootLogin yes\n",
			},
			keyword: "PermitRootLogin",
			want:    main + ":2 no",
		},
		{
			name: "an Include inside a Match block is not followed",
			files: map[string]string{
				main:              "Match User admin\n  Include a.conf\n",
				"/etc/ssh/a.conf": "PermitRootLogin yes\n",
			},
			keyword: "PermitRootLogin",
			want:    "unset",
		},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			cfg := load(t, files(tt.files))
			if got := effective(cfg, tt.keyword); got != tt.want {
				t.Errorf("%s = %q, want %q", tt.keyword, got, tt.want)
			}
		})
	}
}

func TestLoadRecordsMatchBlockDirectivesSeparately(t *testing.T) {
	cfg := load(t, files(map[string]string{sshd.DefaultPath: "PermitRootLogin no\n" +
		"Match User admin\n" +
		"  PermitRootLogin yes\n" +
		"Match Address 10.0.0.0/8\n" +
		"  permitrootlogin prohibit-password\n"}))

	got := cfg.Conditional("PermitRootLogin")
	var lines []int
	for _, d := range got {
		lines = append(lines, d.Line)
	}
	if !slices.Equal(lines, []int{3, 5}) {
		t.Errorf("Conditional lines = %v, want [3 5]", lines)
	}
	if len(cfg.Conditional("X11Forwarding")) != 0 {
		t.Error("Conditional returned directives for a keyword no Match block sets")
	}
}

func TestLoadRejectsInvalidConfiguration(t *testing.T) {
	tests := []struct {
		name    string
		files   map[string]string
		wantErr string
	}{
		{"keyword without argument", map[string]string{sshd.DefaultPath: "Port 22\nPermitRootLogin\n"}, "sshd_config:2: no argument after keyword"},
		{"keyword with only a comment", map[string]string{sshd.DefaultPath: "PermitRootLogin # no\n"}, "no argument after keyword"},
		{"missing keyword", map[string]string{sshd.DefaultPath: "= no\n"}, "missing keyword"},
		{"unterminated quote", map[string]string{sshd.DefaultPath: `Banner "/etc/issue` + "\n"}, "unterminated quoted argument"},
		{
			"error inside an included file names that file",
			map[string]string{sshd.DefaultPath: "Include a.conf\n", "/etc/ssh/a.conf": "\nMaxAuthTries\n"},
			"/etc/ssh/a.conf:2:",
		},
		{"recursive Include", map[string]string{sshd.DefaultPath: "Include sshd_config\n"}, "nested more than 16 levels"},
		{"invalid Include pattern", map[string]string{sshd.DefaultPath: "Include [\n"}, "Include /etc/ssh/["},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, err := sshd.Load(files(tt.files), sshd.DefaultPath)
			if err == nil || !strings.Contains(err.Error(), tt.wantErr) {
				t.Errorf("Load error = %v, want it to contain %q", err, tt.wantErr)
			}
		})
	}
}

func TestLoadBoundsIncludeExpansion(t *testing.T) {
	// Each level includes the next one twice, so the tenth level is read 2^10
	// times at a nesting depth well within OpenSSH's limit.
	fanOut := map[string]string{sshd.DefaultPath: "Include l1.conf l1.conf\n"}
	for i := 1; i < 10; i++ {
		fanOut[fmt.Sprintf("/etc/ssh/l%d.conf", i)] = fmt.Sprintf("Include l%d.conf l%d.conf\n", i+1, i+1)
	}
	fanOut["/etc/ssh/l10.conf"] = "PermitRootLogin no\n"

	// Each copy is 5 MiB, so including it twice exceeds the 8 MiB budget.
	big := strings.Repeat("# filler line\n", 5<<20/14)
	tests := []struct {
		name    string
		files   map[string]string
		wantErr string
	}{
		{"too many files", fanOut, "more than 256 files"},
		{"too many bytes", map[string]string{sshd.DefaultPath: "Include big.conf big.conf\n", "/etc/ssh/big.conf": big}, "exceeds 8 MiB"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, err := sshd.Load(files(tt.files), sshd.DefaultPath)
			if err == nil || !strings.Contains(err.Error(), tt.wantErr) {
				t.Errorf("Load error = %v, want it to contain %q", err, tt.wantErr)
			}
		})
	}
}

func TestLoadReportsUnreadableIncludedFiles(t *testing.T) {
	fsys := files(map[string]string{sshd.DefaultPath: "Include /etc/ssh/sshd_config.d/*.conf\n"})
	fsys["/etc/ssh/sshd_config.d/secret.conf"] = hostfstest.File{Err: fs.ErrPermission}

	_, err := sshd.Load(fsys, sshd.DefaultPath)
	if !errors.Is(err, fs.ErrPermission) {
		t.Errorf("Load error = %v, want fs.ErrPermission", err)
	}
}

func TestLoadReturnsNotExistForMissingConfig(t *testing.T) {
	_, err := sshd.Load(hostfstest.MapFS{}, sshd.DefaultPath)
	if !errors.Is(err, fs.ErrNotExist) {
		t.Errorf("Load error = %v, want fs.ErrNotExist", err)
	}
}

func TestDirectiveStringIsEvidence(t *testing.T) {
	cfg := load(t, files(map[string]string{sshd.DefaultPath: "\n\nPermitRootLogin   yes\n"}))
	d, _ := cfg.Lookup("permitrootlogin")
	if got, want := d.String(), "/etc/ssh/sshd_config:3: PermitRootLogin yes"; got != want {
		t.Errorf("String() = %q, want %q", got, want)
	}
}

func TestParseSeconds(t *testing.T) {
	tests := []struct {
		in      string
		want    int
		wantErr bool
	}{
		{in: "0", want: 0},
		{in: "120", want: 120},
		{in: "30s", want: 30},
		{in: "2m", want: 120},
		{in: "2M", want: 120},
		{in: "1h30m", want: 5400},
		{in: "1m30", want: 90},
		{in: "1d", want: 86400},
		{in: "1w", want: 604800},
		{in: "", wantErr: true},
		{in: "abc", wantErr: true},
		{in: "-5", wantErr: true},
		{in: "10x", wantErr: true},
		{in: "m", wantErr: true},
		{in: "99999999999", wantErr: true},
		{in: "5000w", wantErr: true},
	}
	for _, tt := range tests {
		t.Run(tt.in, func(t *testing.T) {
			got, err := sshd.ParseSeconds(tt.in)
			if (err != nil) != tt.wantErr {
				t.Fatalf("ParseSeconds(%q) error = %v, wantErr %v", tt.in, err, tt.wantErr)
			}
			if got != tt.want {
				t.Errorf("ParseSeconds(%q) = %d, want %d", tt.in, got, tt.want)
			}
		})
	}
}
