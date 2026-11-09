package hostfs_test

import (
	"errors"
	"io/fs"
	"slices"
	"testing"

	"github.com/TahaUsama210/driftwatch/agent/internal/hostfs"
	"github.com/TahaUsama210/driftwatch/agent/internal/hostfs/hostfstest"
)

func TestGlob(t *testing.T) {
	fsys := hostfstest.MapFS{
		"/etc/ssh/sshd_config":                 {},
		"/etc/ssh/sshd_config.d/50-cloud.conf": {},
		"/etc/ssh/sshd_config.d/10-hard.conf":  {},
		"/etc/ssh/sshd_config.d/README":        {},
		"/etc/ssh/sshd_config.d/.old.conf":     {},
		"/etc/ssh/extra/a/x.conf":              {},
		"/etc/ssh/extra/b/x.conf":              {},
		"/etc/ssh/.hidden/x.conf":              {},
	}
	tests := []struct {
		pattern string
		want    []string
	}{
		{"/etc/ssh/sshd_config.d/*.conf", []string{"/etc/ssh/sshd_config.d/10-hard.conf", "/etc/ssh/sshd_config.d/50-cloud.conf"}},
		{"/etc/ssh/sshd_config.d/*", []string{"/etc/ssh/sshd_config.d/10-hard.conf", "/etc/ssh/sshd_config.d/50-cloud.conf", "/etc/ssh/sshd_config.d/README"}},
		{"/etc/ssh/sshd_config.d/.*.conf", []string{"/etc/ssh/sshd_config.d/.old.conf"}},
		{"/etc/ssh/sshd_config", []string{"/etc/ssh/sshd_config"}},
		{"/etc/ssh/extra/*/x.conf", []string{"/etc/ssh/extra/a/x.conf", "/etc/ssh/extra/b/x.conf"}},
		// sshd_config is a file and is skipped, extra has no x.conf, and * does
		// not match the dot directory.
		{"/etc/ssh/*/x.conf", nil},
		{"/etc/ssh/missing.d/*.conf", nil},
		{"/etc/ssh/missing", nil},
	}
	for _, tt := range tests {
		t.Run(tt.pattern, func(t *testing.T) {
			got, err := hostfs.Glob(fsys, tt.pattern)
			if err != nil {
				t.Fatal(err)
			}
			if !slices.Equal(got, tt.want) {
				t.Errorf("Glob(%q) = %q, want %q", tt.pattern, got, tt.want)
			}
		})
	}
}

func TestGlobRejectsBadPatterns(t *testing.T) {
	for _, pattern := range []string{"relative/*.conf", "/etc/[", "/etc/ssh/["} {
		if _, err := hostfs.Glob(hostfstest.MapFS{}, pattern); err == nil {
			t.Errorf("Glob(%q) succeeded, want an error", pattern)
		}
	}
}

func TestGlobReportsUnreadableDirectories(t *testing.T) {
	fsys := failingReadDir{MapFS: hostfstest.MapFS{"/etc/ssh/sshd_config.d/a.conf": {}}}

	_, err := hostfs.Glob(fsys, "/etc/ssh/sshd_config.d/*.conf")
	if !errors.Is(err, fs.ErrPermission) {
		t.Errorf("error = %v, want fs.ErrPermission", err)
	}
}

func TestGlobReportsUnstatableLiteralPaths(t *testing.T) {
	fsys := hostfstest.MapFS{"/etc/ssh/sshd_config": {Err: fs.ErrPermission}}

	_, err := hostfs.Glob(fsys, "/etc/ssh/sshd_config")
	if !errors.Is(err, fs.ErrPermission) {
		t.Errorf("error = %v, want fs.ErrPermission", err)
	}
}

type failingReadDir struct{ hostfstest.MapFS }

func (failingReadDir) ReadDir(name string) ([]string, error) {
	return nil, &fs.PathError{Op: "readdir", Path: name, Err: fs.ErrPermission}
}
