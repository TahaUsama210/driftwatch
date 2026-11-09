package hostfs_test

import (
	"bytes"
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"runtime"
	"slices"
	"strings"
	"testing"

	"github.com/TahaUsama210/driftwatch/agent/internal/hostfs"
)

// newRoot creates a directory tree from host paths to contents and returns
// its location.
func newRoot(t *testing.T, files map[string]string) string {
	t.Helper()
	root := t.TempDir()
	for name, content := range files {
		local := filepath.Join(root, filepath.FromSlash(name))
		if err := os.MkdirAll(filepath.Dir(local), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(local, []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	return root
}

// symlink creates a symbolic link inside root, skipping the test where the
// platform or user cannot create one (unprivileged Windows).
func symlink(t *testing.T, root, target, name string) {
	t.Helper()
	local := filepath.Join(root, filepath.FromSlash(name))
	if err := os.MkdirAll(filepath.Dir(local), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(filepath.FromSlash(target), local); err != nil {
		if runtime.GOOS == "windows" {
			t.Skipf("cannot create symlinks here: %v", err)
		}
		t.Fatal(err)
	}
}

func TestDirReadsFilesRelativeToRoot(t *testing.T) {
	root := newRoot(t, map[string]string{"/etc/hostname": "web-1\n"})

	got, err := hostfs.Dir(root).ReadFile("/etc/hostname")
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != "web-1\n" {
		t.Errorf("ReadFile = %q, want %q", got, "web-1\n")
	}
}

func TestDirErrorsNameHostPathsNotMountPaths(t *testing.T) {
	root := newRoot(t, nil)

	_, err := hostfs.Dir(root).ReadFile("/etc/shadow")

	if !errors.Is(err, fs.ErrNotExist) {
		t.Fatalf("error = %v, want fs.ErrNotExist", err)
	}
	if !strings.Contains(err.Error(), "/etc/shadow") || strings.Contains(err.Error(), root) {
		t.Errorf("error %q should name /etc/shadow and not the mount point %s", err, root)
	}
}

func TestDirRejectsRelativePaths(t *testing.T) {
	_, err := hostfs.Dir(t.TempDir()).ReadFile("etc/passwd")
	if !errors.Is(err, fs.ErrInvalid) {
		t.Errorf("error = %v, want fs.ErrInvalid", err)
	}
}

func TestDirRefusesFilesOverTheSizeCap(t *testing.T) {
	big := strings.Repeat("x", hostfs.MaxFileSize+1)
	root := newRoot(t, map[string]string{"/etc/huge": big})

	if _, err := hostfs.Dir(root).ReadFile("/etc/huge"); err == nil {
		t.Error("ReadFile succeeded for a file over the size cap")
	}
}

func TestDirReadDirReturnsSortedNames(t *testing.T) {
	root := newRoot(t, map[string]string{
		"/etc/ssh/sshd_config.d/b.conf": "",
		"/etc/ssh/sshd_config.d/a.conf": "",
	})

	got, err := hostfs.Dir(root).ReadDir("/etc/ssh/sshd_config.d")
	if err != nil {
		t.Fatal(err)
	}
	if want := []string{"a.conf", "b.conf"}; !slices.Equal(got, want) {
		t.Errorf("ReadDir = %q, want %q", got, want)
	}
}

func TestDirStatReportsModeAndOwner(t *testing.T) {
	root := newRoot(t, map[string]string{"/etc/passwd": "root:x:0:0::/root:/bin/sh\n"})
	if err := os.Chmod(filepath.Join(root, "etc", "passwd"), 0o600); err != nil {
		t.Fatal(err)
	}

	fi, err := hostfs.Dir(root).Stat("/etc/passwd")

	if runtime.GOOS == "windows" {
		if !errors.Is(err, errors.ErrUnsupported) {
			t.Errorf("Stat error = %v, want errors.ErrUnsupported on Windows", err)
		}
		return
	}
	if err != nil {
		t.Fatal(err)
	}
	if fi.Mode.Perm() != 0o600 {
		t.Errorf("mode = %04o, want 0600", fi.Mode.Perm())
	}
	if want := uint32(os.Getuid()); fi.UID != want {
		t.Errorf("uid = %d, want %d", fi.UID, want)
	}
}

func TestDirResolvesSymlinksInsideTheRoot(t *testing.T) {
	tests := []struct {
		name   string
		target string // link target as stored on the host
	}{
		{"relative target", "../usr/lib/os-release"},
		// On the scanned host /usr/lib/os-release is under the mount point,
		// not the scanner's own /usr/lib/os-release.
		{"absolute target", "/usr/lib/os-release"},
		{"dot-dot cannot climb above the root", "../../../../usr/lib/os-release"},
	}
	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			root := newRoot(t, map[string]string{"/usr/lib/os-release": "ID=test\n"})
			symlink(t, root, tt.target, "/etc/os-release")

			got, err := hostfs.Dir(root).ReadFile("/etc/os-release")
			if err != nil {
				t.Fatal(err)
			}
			if !bytes.Equal(got, []byte("ID=test\n")) {
				t.Errorf("ReadFile = %q, want the file inside the root", got)
			}
		})
	}
}

func TestDirResolvesSymlinkedDirectories(t *testing.T) {
	root := newRoot(t, map[string]string{"/opt/ssh/sshd_config": "Port 22\n"})
	symlink(t, root, "/opt/ssh", "/etc/ssh")

	got, err := hostfs.Dir(root).ReadFile("/etc/ssh/sshd_config")
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != "Port 22\n" {
		t.Errorf("ReadFile = %q", got)
	}
}

func TestDirFailsOnSymlinkLoops(t *testing.T) {
	root := newRoot(t, nil)
	symlink(t, root, "/etc/b", "/etc/a")
	symlink(t, root, "/etc/a", "/etc/b")

	if _, err := hostfs.Dir(root).ReadFile("/etc/a"); err == nil {
		t.Error("ReadFile succeeded on a symlink loop")
	}
}
