//go:build unix

package hostfs_test

import (
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"

	"github.com/TahaUsama210/driftwatch/agent/internal/hostfs"
)

func TestDirRefusesToReadAFIFO(t *testing.T) {
	root := newRoot(t, map[string]string{"/etc/hostname": "web-1\n"})
	if err := syscall.Mkfifo(filepath.Join(root, "etc", "login.defs"), 0o644); err != nil {
		t.Fatal(err)
	}
	symlink(t, root, "/etc/login.defs", "/etc/os-release")

	for _, name := range []string{"/etc/login.defs", "/etc/os-release"} {
		t.Run(name, func(t *testing.T) {
			done := make(chan error, 1)
			go func() {
				_, err := hostfs.Dir(root).ReadFile(name)
				done <- err
			}()
			select {
			case err := <-done:
				if err == nil || !strings.Contains(err.Error(), "not a regular file") {
					t.Errorf("ReadFile error = %v, want a not-a-regular-file error", err)
				}
			case <-time.After(5 * time.Second):
				t.Fatal("ReadFile blocked on a FIFO with no writer")
			}
		})
	}
}
